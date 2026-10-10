package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityInfo;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Optional;

/**
 * {@code tacz.gun}:用 TACZ 枪械——现在有哪些枪、把包里的枪拿到手上、朝一只实体开枪、换弹、切开火模式、开/关瞄准。
 *
 * <p>全在服务端。开枪走 TACZ 的 {@code IGunOperator} 直接驱动同伴(服务端假玩家),不依赖客户端网络包;伤人的那一下照
 * 规矩交由权限层裁决({@code Action.attack})——要打的主人、宠物、村民那些,主人没放行就开不了这一枪,也就不会误伤。
 *
 * <p>枪是远程武器,开枪<b>不量距离、不走位</b>:打得中打不中取决于站的位置和看不看得见目标,那些由程序自己用
 * {@code numen.move.to} 与 {@code numen.scan.sight} 先办好。本组里的改装备(拿枪、换弹、瞄准)是同伴自己的装备,不问主人;
 * 只有"对谁开枪"这一件伤人,过权限层。
 */
public final class TaczApi {

    private TaczApi() {}

    public static void install(NumenApi numen) {
        numen.api("gun", "TACZ firearms: list them, take one in hand, shoot at an entity, reload, switch fire mode, aim.",
                TaczApi.class);
    }

    /** 现况:有哪些枪、手上是什么。 */
    @Doc("The TACZ guns on this pack and what you hold now.")
    public record Options(@Doc("Every gun id TACZ knows, sorted.") List<String> guns,
                          @Doc("The gun in your main hand now; none when you hold no TACZ gun.")
                          Optional<String> mainHand,
                          @Doc("Ammo left in the gun in your main hand; absent when you hold no TACZ gun.")
                          Optional<Integer> ammo) {}

    /**
     * 有哪些枪、手上是什么。只读。枪 id 是装枪械包才有的清单,查一次即可;清单不写进帮助里,因为跟着装的包变。
     * 手上拿的"是不是某把枪"能读,但枪里剩多少发只有拿在主手时读得到,而且服务端读不到弹匣之外的备弹。
     */
    @Fn("The TACZ guns on this pack, and what gun you hold with its ammo left.")
    @Example("tacz.gun.options()")
    @Example("for _, g in ipairs(tacz.gun.options().guns) do print(g) end")
    @Note("Read-only. The ids come from the gun packs installed; pick one exactly as listed.")
    @Note("Ammo is read from the gun in hand only; TACZ does not tell the server about spare magazines.")
    @SeeAlso({"tacz.gun.equip", "tacz.gun.shoot"})
    public static Options options(ServerCall call) {
        NumenPlayer her = call.her();
        String mainHand = null;
        Integer ammo = null;
        com.tacz.guns.api.item.IGun gun = com.tacz.guns.api.item.IGun.getIGunOrNull(her.getMainHandItem());
        if (gun != null) {
            mainHand = gun.getGunId(her.getMainHandItem()).toString();
            ammo = gun.getCurrentAmmoCount(her.getMainHandItem());
        }
        return new Options(Tacz.gunIds(), Optional.ofNullable(mainHand), Optional.ofNullable(ammo));
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
                        @Doc("What TACZ said: success, no_ammo, cool_down, not_draw, not_gun, reloading, …")
                        String result) {}

    /**
     * 朝一只实体开一枪:先过权限层(攻击那只实体),放行了再算指向它的方向、交给 TACZ 开枪。
     * 不走路、不量距离——打得中打不中取决于你站哪、看不看得见;先 {@code numen.scan.sight} 确认、{@code numen.move.to}
     * 走过去,再打。
     */
    @Fn("Shoot at one entity with the gun in your main hand.")
    @Example("tacz.gun.shoot(184)")
    @Example("local e = numen.scan.entities(\"hostile\")[1]; tacz.gun.shoot(e)")
    @Note("It uses the gun you hold (tacz.gun.equip first). One trigger pull, not a burst.")
    @Note("Attacking asks your owner first when it should: shooting your owner's pets, named mobs or villagers is "
            + "decided by the permission layer, not by you.")
    @Note("It does not travel and does not check distance: hit or miss depends on where you stand and whether you can "
            + "see it (numen.scan.sight). The result TACZ reports (no_ammo, cool_down, …) tells why a shot did not fire.")
    @SeeAlso({"tacz.gun.equip", "numen.scan.sight", "numen.fight.attack"})
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

    /** 换弹。 */
    public record NoArgs() {}

    /** 给主手的枪换弹。 */
    @Fn("Reload the gun in your main hand.")
    @Example("tacz.gun.reload()")
    @Note("TACZ decides whether a reload can start (ammo full, still reloading, cool-down). It only starts the reload; "
            + "whether it finished is not reported back.")
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
