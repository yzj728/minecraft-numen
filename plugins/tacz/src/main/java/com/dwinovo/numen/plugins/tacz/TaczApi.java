package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tacz.gun}:用 TACZ 枪械——现在有哪些枪、背包里带着哪几把、把枪拿到手上、朝一只实体开枪、朝面朝方向开枪、拉栓、
 * 换弹、切开火模式、开/关瞄准。
 *
 * <p>全在服务端。开枪走 TACZ 的 {@code IGunOperator} 直接驱动同伴(服务端假玩家),不依赖客户端网络包;伤人的那一下照
 * 规矩交由权限层裁决({@code Action.attack})——要打的主人、宠物、村民那些,主人没放行就开不了这一枪,也就不会误伤。
 *
 * <p>枪是远程武器,开枪<b>不量距离、不走位</b>:打得中打不中取决于站的位置和看不看得见目标,那些由程序自己用
 * {@code numen.scan.sight} 先看好。本组里的改装备(拿枪、拉栓、换弹、瞄准)是同伴自己的装备,不问主人;只有"对谁开枪"
 * 这一件伤人,过权限层。
 *
 * <p><b>shoot 是一段自动的开火短活</b>,状态机照车万女仆验证过的枪械逻辑逐条对齐:手动枪膛空就拉栓、弹匣空就换弹、
 * 没切枪就切枪、狙击枪先开镜、等冷却/换弹/拉栓收尾、疾跑就站定——直到子弹出膛,或实在开不了枪(没弹药、枪不在手上)。
 * 派发那一刻就按住战斗与逃跑两条本能,开火期间身体不被抢、不乱跑。模型不必逐码处理,一次调用拿最终结果。手动
 * bolt/reload/aim 仍然单独暴露,供精细控制。
 */
public final class TaczApi {

    /** 自卫链的名字(见 core 的 {@code MobDefenseChain.ID});本模块编译期看不见 core,照 id 按住。 */
    private static final String MOB_DEFENSE = "mob_defense";
    /** 逃跑链的名字(见 core 的 {@code FleeChain.ID})。 */
    private static final String FLEE = "flee";

    private TaczApi() {}

    public static void install(NumenApi numen) {
        numen.api("gun", "TACZ firearms: list them and what you carry, take one in hand, shoot at an entity (auto "
                + "bolt/reload/scope), fire where you face, work the bolt, reload, switch fire mode, aim.", TaczApi.class);
    }

    /** 现况:有哪些枪、背包里带着哪几把、手上是什么、枪此刻什么状态。 */
    @Doc("The TACZ guns on this pack, the ones you carry, and the state of the gun you hold.")
    public record Options(@Doc("Every gun id TACZ knows, sorted.") List<String> guns,
                          @Doc("The guns in your inventory, gun id to how many you carry (main hand, offhand and "
                                  + "backpack all count). Empty when you carry none.")
                          Map<String, Integer> carried,
                          @Doc("The gun in your main hand now; none when you hold no TACZ gun.")
                          Optional<String> mainHand,
                          @Doc("Rounds in the gun's magazine; absent when you hold no TACZ gun.")
                          Optional<Integer> ammo,
                          @Doc("Whether a round is in the chamber. A manual-action gun with none here refuses to "
                                  + "shoot until the bolt is worked (shoot does that for you).")
                          Optional<Boolean> bulletInBarrel,
                          @Doc("Whether a reload is in progress.")
                          Optional<Boolean> reloading,
                          @Doc("Whether the bolt is being worked.")
                          Optional<Boolean> bolting,
                          @Doc("Whether you are sprinting; shooting waits for you to stand still.")
                          Optional<Boolean> sprinting,
                          @Doc("Milliseconds until the gun can fire again (0 = ready).")
                          Optional<Long> coolDownMs) {}

    /**
     * 有哪些枪、你带着哪几把、手上是什么、枪的状态。只读。枪 id 是装枪械包才有的清单;{@code carried} 是
     * 你背包里真实带着的枪,两把不同的枪在那里是两条不同的 id——切换武器就是拿另一条的 id 去 equip。
     * 手上拿的枪能读弹匣余弹、膛内有没有子弹、换弹/拉栓/疾跑/冷却状态;备弹(包里的弹匣/弹药盒)读不到。
     */
    @Fn("The TACZ guns on this pack, the guns you carry (id to count), and the state of the gun you hold: ammo, "
            + "chamber, reloading, bolting, sprinting, shoot cool-down.")
    @Example("tacz.gun.options()")
    @Example("for _, g in ipairs(tacz.gun.options().guns) do print(g) end")
    @Example("local o = tacz.gun.options(); for id, n in pairs(o.carried) do print(id, n) end")
    @Note("Read-only. The ids come from the gun packs installed; pick one exactly as listed.")
    @Note("carried lists each gun you actually carry by its own id — two different guns are two different ids there.")
    @Note("Ammo is read from the gun in hand only; TACZ does not tell the server about spare magazines.")
    @Note("You rarely need to poll this before shooting: shoot handles the gun's state on its own.")
    @SeeAlso({"tacz.gun.equip", "tacz.gun.shoot"})
    public static Options options(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack stack = her.getMainHandItem();
        com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
        Map<String, Integer> carried = Tacz.carriedGuns(her);
        if (gun == null) {
            return new Options(Tacz.gunIds(), carried, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        Tacz.Held held = Tacz.held(her, stack);
        return new Options(Tacz.gunIds(), carried, Optional.of(gun.getGunId(stack).toString()),
                Optional.of(gun.getCurrentAmmoCount(stack)),
                Optional.of(held.bulletInBarrel()), Optional.of(held.reloading()), Optional.of(held.bolting()),
                Optional.of(held.sprinting()), Optional.of(held.coolDownMs()));
    }

    /** 拿哪把枪。 */
    public record Equip(@Doc("The gun id, exactly as tacz.gun.options lists it — the one you carry.") String gun) {}

    /**
     * 把背包里那把枪拿到主手。不凭空造枪:背包里没有就明确失败,问你主人要。改的是你自己的装备,不碰别人、不碰世界。
     */
    @Fn("Take a TACZ gun you already carry into your main hand.")
    @Example("tacz.gun.equip(\"tacz:ak47\")")
    @Note("It takes a gun already in your inventory; it never creates one. If you do not carry it, it fails and you should "
            + "ask your owner for the gun.")
    @Note("You must carry it: tacz.gun.options().carried lists the guns you carry, each under its own id.")
    @SeeAlso({"tacz.gun.options", "tacz.gun.shoot"})
    public static void equip(ServerCall call, Equip args) {
        String gun = args.gun();
        if (!Tacz.gunIds().contains(gun)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "TACZ has no gun " + gun + "; tacz.gun.options lists the installed "
                    + "ones", Call.of("tacz.gun.options"));
        }
        NumenPlayer her = call.her();
        if (!Tacz.mainHandIs(her, gun) && Tacz.findGunSlot(her, gun) < 0) {
            throw new ApiError(ErrorKind.NOT_FOUND, "you do not carry " + gun + "; it is not created for you. Ask your "
                    + "owner for the gun, or check tacz.gun.options().carried", null);
        }
        Tacz.equip(her, gun);
    }

    /** 朝谁开枪。 */
    public record Shoot(@Doc("The entity to shoot at: an Entity from numen.scan.entities, or its id.") EntityRef target) {}

    /** 这一枪的结果。 */
    @Doc("What one shot did.")
    public record Fired(@Doc("The entity you shot at; -1 when it was a shot at nothing (tacz.gun.fire).") int target,
                        @Doc("What TACZ said: success, or why it could not fire — no_ammo, not_gun, …")
                        String result) {}

    /**
     * 朝一只实体开一枪。先过权限层(攻击那只实体),放行了就派一段自动开火短活,状态机照车万女仆验证过的枪械逻辑:
     * 手动枪膛空先拉栓、弹匣空先换弹、没切枪先切枪、狙击枪先开镜、等冷却与换弹/拉栓收尾、疾跑站定、身体转向目标
     * ——直到子弹出膛,或者实在开不了(没弹药、手上没枪)。派发那一刻就按住战斗与逃跑两条本能,开火期间身体不被抢、
     * 不乱跑。
     *
     * <p>不走路、不量距离:打得中打不中取决于你站哪、看不看得见;先 {@code numen.scan.sight} 看好,看得见就直接打,
     * 看不见才走位。
     */
    @Fn("Shoot at one entity with the gun in your main hand, readying the gun itself (bolt, reload, scope) as needed.")
    @Example("tacz.gun.shoot(184)")
    @Example("local e = numen.scan.entities(\"hostile\")[1]; tacz.gun.shoot(e)")
    @Note("One shot per call: it fires once and reports. For several shots, call it once for each.")
    @Note("It uses the gun you hold (tacz.gun.equip first) and readies it on its own: manual-action guns are chambered, "
            + "empty magazines are reloaded if you carry matching ammo, sniper rifles are scoped in before the shot.")
    @Note("It turns your body to face the target, so the bullet flies where the gun points.")
    @Note("While it fires, your combat and flee instincts stand down, so nothing else grabs the body mid-shot — even "
            + "when a zombie is chasing you.")
    @Note("Attacking asks your owner first when it should: shooting your owner's pets, named mobs or villagers is "
            + "decided by the permission layer, not by you.")
    @Note("It does not travel and does not check distance: hit or miss depends on where you stand and whether you can "
            + "see it (numen.scan.sight). When it could not fire at all, it fails and says why (no ammo, no gun, …).")
    @SeeAlso({"tacz.gun.equip", "tacz.gun.fire", "tacz.gun.options", "numen.scan.sight", "numen.fight.attack"})
    public static Pending<Fired> shoot(ServerCall call, Shoot args) {
        Entity target = call.entity(args.target());
        if (!(target instanceof LivingEntity)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, target.getName().getString() + " is not a living thing you can "
                    + "shoot at", null);
        }
        int targetId = target.getId();
        // 开火期间身体归任务管:派发这一刻就按住战斗与逃跑两条本能——它们在任务拿到身体
        // 之前就可能先抢(怪一逼近自卫链就上),等到任务 tick 里再按就晚了。任务结束时
        // 引擎在身体空闲时统一解除,不用显式还。
        call.her().pauseReflex(MOB_DEFENSE);
        call.her().pauseReflex(FLEE);
        // 伤人先过权限层;放行了把开火短活派下去(它自己重新解析目标、摆对枪的状态)
        return call.authorize(Action.attack(target)).thenWait(allowed -> call.sync(new ShootRecord(call, targetId)));
    }

    /** 朝面朝方向开一枪(无目标)。 */
    @Fn("Fire the gun in your main hand straight ahead, where you face — no target needed.")
    @Example("tacz.gun.fire()")
    @Note("No target: the shot follows your current facing. Use it to test a gun, to fire a warning shot, or to shoot "
            + "at nothing in particular.")
    @Note("It does not aim and does not check for friendlies: for shooting at an entity use tacz.gun.shoot, which "
            + "aims and goes through the permission layer.")
    @Note("It still needs the gun ready (equipped, ammo); the result says why when it did not fire.")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.equip"})
    public static Fired fire(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack stack = her.getMainHandItem();
        com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
        if (gun == null) {
            throw new ApiError(ErrorKind.FAILED, "you hold no gun; tacz.gun.equip first", Call.of("tacz.gun.equip"));
        }
        String result = Tacz.fire(her).name().toLowerCase(java.util.Locale.ROOT);
        return new Fired(-1, result);
    }

    /** 拉栓:手动枪把下一发顶上膛。 */
    @Fn("Work the bolt of the gun in hand: chamber the next round.")
    @Example("tacz.gun.bolt()")
    @Note("Manual-action guns need this between shots; tacz.gun.shoot does it for you, so this is for fine control.")
    @Note("Automatic guns chamber on their own; working the bolt on one does nothing.")
    @Note("It only starts the bolt; whether it finished is not reported back. Check tacz.gun.options().bolting.")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.options"})
    public static void bolt(ServerCall call) {
        Tacz.bolt(call.her());
    }

    /** 换弹。 */
    @Fn("Reload the gun in your main hand.")
    @Example("tacz.gun.reload()")
    @Note("TACZ decides whether a reload can start (ammo full, still reloading, cool-down). It only starts the reload; "
            + "whether it finished is not reported back — tacz.gun.options().reloading says when it is done.")
    @Note("The client-side auto-reload setting does not apply to you (you have no client): reload is always manual — "
            + "but tacz.gun.shoot reloads on its own when the magazine is empty.")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.options"})
    public static void reload(ServerCall call) {
        Tacz.reload(call.her());
    }

    /** 切下一个开火模式(单发/全自动/连发之间轮换)。 */
    @Fn("Cycle the fire mode of the gun in hand: full-auto, semi, burst.")
    @Example("tacz.gun.fire_select()")
    @Note("What mode a gun supports is decided by the gun's data; cycling just moves to TACZ's next one.")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.options"})
    public static void fireSelect(ServerCall call) {
        Tacz.fireSelect(call.her());
    }

    /** 开/关瞄准。 */
    public record Aim(@Doc("true starts aiming down the sights, false stops.") boolean aim) {}

    /** 开镜/收镜。 */
    @Fn("Start or stop aiming down the gun's sights.")
    @Example("tacz.gun.aim(true)")
    @Example("tacz.gun.aim(false)")
    @Note("Aiming affects the gun's spread; it does not by itself fire anything.")
    @Note("Sniper rifles are scoped in automatically by tacz.gun.shoot; use this for the other guns or to scope out.")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.options"})
    public static void aim(ServerCall call, Aim args) {
        Tacz.aim(call.her(), args.aim());
    }
}
