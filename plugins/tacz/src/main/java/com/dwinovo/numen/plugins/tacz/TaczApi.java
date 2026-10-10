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
import java.util.Optional;

/**
 * {@code tacz.gun}:用 TACZ 枪械——现在有哪些枪、把包里的枪拿到手上、朝一只实体开枪、拉栓、换弹、切开火模式、开/关瞄准。
 *
 * <p>全在服务端。开枪走 TACZ 的 {@code IGunOperator} 直接驱动同伴(服务端假玩家),不依赖客户端网络包;伤人的那一下照
 * 规矩交由权限层裁决({@code Action.attack})——要打的主人、宠物、村民那些,主人没放行就开不了这一枪,也就不会误伤。
 *
 * <p>枪是远程武器,开枪<b>不量距离、不走位</b>:打得中打不中取决于站的位置和看不看得见目标,那些由程序自己用
 * {@code numen.move.to} 与 {@code numen.scan.sight} 先办好。本组里的改装备(拿枪、拉栓、换弹、瞄准)是同伴自己的装备,
 * 不问主人;只有"对谁开枪"这一件伤人,过权限层。
 *
 * <p><b>枪是状态机</b>:TACZ 的 shoot 有一串前置检查,不满足时一枪不开、只回结果码(换弹中 is_reloading、拉栓中
 * is_bolting、疾跑中 is_sprinting、手动枪没上膛 need_bolt、冷却 cool_down、没子弹 no_ammo、没拿枪 not_gun/not_draw)。
 * 结果码就是给模型的下一步指令:看码把状态摆对再开下一枪,不猜、不无脑重试。
 */
public final class TaczApi {

    private TaczApi() {}

    public static void install(NumenApi numen) {
        numen.api("gun", "TACZ firearms: list them, take one in hand, shoot at an entity, work the bolt, reload, "
                + "switch fire mode, aim.", TaczApi.class);
    }

    /** 现况:有哪些枪、手上是什么、枪此刻什么状态。 */
    @Doc("The TACZ guns on this pack and the state of the gun you hold.")
    public record Options(@Doc("Every gun id TACZ knows, sorted.") List<String> guns,
                          @Doc("The gun in your main hand now; none when you hold no TACZ gun.")
                          Optional<String> mainHand,
                          @Doc("Rounds in the gun's magazine; absent when you hold no TACZ gun.")
                          Optional<Integer> ammo,
                          @Doc("Whether a round is in the chamber. A manual-action gun with none here refuses to "
                                  + "shoot (need_bolt): work the bolt first.")
                          Optional<Boolean> bulletInBarrel,
                          @Doc("Whether a reload is in progress; shooting now says is_reloading.")
                          Optional<Boolean> reloading,
                          @Doc("Whether the bolt is being worked; shooting now says is_bolting.")
                          Optional<Boolean> bolting,
                          @Doc("Whether you are sprinting; shooting now says is_sprinting — stand still first.")
                          Optional<Boolean> sprinting,
                          @Doc("Milliseconds until the gun can fire again (0 = ready).")
                          Optional<Long> coolDownMs) {}

    /**
     * 有哪些枪、手上是什么、枪的状态。只读。枪 id 是装枪械包才有的清单,查一次即可。手上拿的枪能读弹匣余弹、
     * 膛内有没有子弹、换弹/拉栓/疾跑/冷却状态;备弹(包里的弹匣/弹药盒)读不到,那是 TACZ 不告诉服务端的。
     */
    @Fn("The TACZ guns on this pack, and the state of the gun you hold: ammo, chamber, reloading, bolting, sprinting, "
            + "shoot cool-down.")
    @Example("tacz.gun.options()")
    @Example("for _, g in ipairs(tacz.gun.options().guns) do print(g) end")
    @Note("Read-only. The ids come from the gun packs installed; pick one exactly as listed.")
    @Note("Ammo is read from the gun in hand only; TACZ does not tell the server about spare magazines.")
    @Note("Before shooting, check this: reloading or bolting means wait, sprinting means stand still, cool_down_ms "
            + "means wait that long.")
    @SeeAlso({"tacz.gun.equip", "tacz.gun.shoot", "tacz.gun.bolt"})
    public static Options options(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack stack = her.getMainHandItem();
        com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(stack);
        if (gun == null) {
            return new Options(Tacz.gunIds(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        Tacz.Held held = Tacz.held(her, stack);
        return new Options(Tacz.gunIds(), Optional.of(gun.getGunId(stack).toString()),
                Optional.of(gun.getCurrentAmmoCount(stack)),
                Optional.of(held.bulletInBarrel()), Optional.of(held.reloading()), Optional.of(held.bolting()),
                Optional.of(held.sprinting()), Optional.of(held.coolDownMs()));
    }

    /** 拿哪把枪。 */
    public record Equip(@Doc("The gun id, exactly as tacz.gun.options lists it.") String gun) {}

    /**
     * 把背包里那把枪拿到主手。不凭空造枪:背包里没有就明确失败,问你主人要。改的是你自己的装备,不碰别人、不碰世界。
     */
    @Fn("Take a TACZ gun you already carry into your main hand.")
    @Example("tacz.gun.equip(\"tacz:ak47\")")
    @Note("It takes a gun already in your inventory; it never creates one. If you do not carry it, it fails and you should "
            + "ask your owner for the gun.")
    @Note("You must carry it: find it with numen.inv, or have your owner hand one over first.")
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
                    + "owner for the gun, or look it up with numen.inv", null);
        }
        Tacz.equip(her, gun);
    }

    /** 朝谁开枪。 */
    public record Shoot(@Doc("The entity to shoot at: an Entity from numen.scan.entities, or its id.") EntityRef target) {}

    /** 这一枪的结果。 */
    @Doc("What one shot did.")
    public record Fired(@Doc("The entity you shot at.") int target,
                        @Doc("What TACZ said: success, or why it did not fire — no_ammo, cool_down, need_bolt, "
                                + "not_draw, not_gun, is_reloading, is_bolting, is_sprinting, overheated.")
                        String result) {}

    /**
     * 朝一只实体开一枪:先过权限层(攻击那只实体),放行了再算指向它的方向、交给 TACZ 开枪。
     * 不走路、不量距离——打得中打不中取决于你站哪、看不看得见;先 {@code numen.scan.sight} 确认、{@code numen.move.to}
     * 走过去,再打。
     *
     * <p>没开出去的结果码就是下一步:need_bolt → {@code tacz.gun.bolt} 拉栓;is_reloading/is_bolting → 等完再打;
     * is_sprinting → 站定再打;cool_down → 等冷却;no_ammo → {@code tacz.gun.reload} 换弹;not_gun/not_draw →
     * {@code tacz.gun.equip} 先拿枪。
     */
    @Fn("Shoot at one entity with the gun in your main hand.")
    @Example("tacz.gun.shoot(184)")
    @Example("local e = numen.scan.entities(\"hostile\")[1]; tacz.gun.shoot(e)")
    @Note("It uses the gun you hold (tacz.gun.equip first). One trigger pull, not a burst.")
    @Note("Attacking asks your owner first when it should: shooting your owner's pets, named mobs or villagers is "
            + "decided by the permission layer, not by you.")
    @Note("It does not travel and does not check distance: hit or miss depends on where you stand and whether you can "
            + "see it (numen.scan.sight). The result TACZ reports tells exactly why a shot did not fire — read it and "
            + "do what it says (need_bolt means tacz.gun.bolt, no_ammo means tacz.gun.reload, …).")
    @SeeAlso({"tacz.gun.equip", "tacz.gun.bolt", "tacz.gun.reload", "numen.scan.sight", "numen.fight.attack"})
    public static Pending<Fired> shoot(ServerCall call, Shoot args) {
        Entity target = call.entity(args.target());
        if (!(target instanceof LivingEntity living)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, target.getName().getString() + " is not a living thing you can "
                    + "shoot at", null);
        }
        int targetId = target.getId();
        return call.authorize(Action.attack(target)).then(allowed -> {
            // 主人答复回来时它可能走开了:按同一个编号再认一次
            Entity still = call.entity(EntityRef.id(targetId));
            NumenPlayer her = call.her();
            com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(her.getMainHandItem());
            if (gun == null) {
                throw new ApiError(ErrorKind.FAILED, "you hold no gun; tacz.gun.equip first", Call.of("tacz.gun.equip"));
            }
            String result = Tacz.shoot(her, (LivingEntity) still).name().toLowerCase(java.util.Locale.ROOT);
            return new Fired(still.getId(), result);
        });
    }

    /** 拉栓:手动枪把下一发顶上膛。 */
    @Fn("Work the bolt of the gun in hand: chamber the next round.")
    @Example("tacz.gun.bolt()")
    @Note("Manual-action guns need this before the first shot (shoot says need_bolt) and after each shot. Automatic "
            + "guns chamber on their own; working the bolt on one does nothing.")
    @Note("It only starts the bolt; whether it finished is not reported back. Check tacz.gun.options().bolting before "
            + "you shoot.")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.options"})
    public static void bolt(ServerCall call) {
        Tacz.bolt(call.her());
    }

    /** 换弹。 */
    @Fn("Reload the gun in your main hand.")
    @Example("tacz.gun.reload()")
    @Note("TACZ decides whether a reload can start (ammo full, still reloading, cool-down). It only starts the reload; "
            + "whether it finished is not reported back — tacz.gun.options().reloading says when it is done.")
    @Note("The client-side auto-reload setting does not apply to you (you have no client): reload is always manual.")
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
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.options"})
    public static void aim(ServerCall call, Aim args) {
        Tacz.aim(call.her(), args.aim());
    }
}
