package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.api.combat.Firearms;
import com.dwinovo.numen.api.combat.RangedFirearm;
import com.dwinovo.numen.entity.NumenPlayer;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.GunTabType;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把 TACZ 枪接进 Numen 自己的战斗系统(经 {@link Firearms} 登记)。登记之后,{@code Loadout}
 * 把枪当远程武器、逃跑链把她当"有武器"、{@code numen.fight.attack} 与自卫本能开的那场仗,
 * 都走原版那套验证过的走位/朝向/站定,开火则每服务端刻问到这里一次。
 *
 * <h2>状态机照车万女仆验证过的枪械逻辑</h2>
 * 女仆的 {@code TacInnerCompat.performGunAttack} 是 TACZ 上验证过能打中的打法:枪没登记就切、
 * 手动枪膛空拉栓、没子弹换弹、狙击枪先开镜、冷却/换弹/拉栓中就等。这里逐条照做,状态全从
 * TACZ 存在实体上的数据读(它自己的 tick 在推进换弹/拉栓/开镜进度),本实现不另造状态。
 *
 * <h2>开火那一刻才转身</h2>
 * 子弹方向由传给 shoot 的俯仰/偏航决定,但玩家看到的枪口朝向得和它一致——开火那一拍用原版
 * {@code lookAt(EYES)} 转身(和 Numen 弓战斗"松手前才瞄"同一个道理,每刻转会带偏移动)。
 *
 * <h2>交战距离按枪型给</h2>
 * 女仆的判据:狙击远距离、喷子/手枪/冲锋枪近距离、其余中距离。这里同款分档,战斗走位环照它定——
 * 目标在射程内有视线就原地开枪,而不是贴脸挨打或跑来跑去。
 */
final class TacFirearm implements RangedFirearm {

    /** 已经对谁、对哪把枪试过换弹仍然没弹——再报 no_ammo 就真没有。按枪 id 记,换枪就重新武装。 */
    private final Map<Integer, String> reloadTried = new HashMap<>();

    @Override
    public boolean isFirearm(ItemStack stack) {
        return IGun.getIGunOrNull(stack) != null;
    }

    @Override
    public double engagementRange(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return 16.0;
        }
        String type = TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> index.getType()).orElse("");
        // 照车万女仆的分档:狙击最远,喷子最近,其余居中
        if (GunTabType.SNIPER.name().toLowerCase(Locale.ENGLISH).equals(type)) {
            return 24.0;
        }
        if (GunTabType.SHOTGUN.name().toLowerCase(Locale.ENGLISH).equals(type)) {
            return 8.0;
        }
        if (GunTabType.PISTOL.name().toLowerCase(Locale.ENGLISH).equals(type)) {
            return 10.0;
        }
        if (GunTabType.SMG.name().toLowerCase(Locale.ENGLISH).equals(type)) {
            return 12.0;
        }
        if (GunTabType.MG.name().toLowerCase(Locale.ENGLISH).equals(type)) {
            return 18.0;
        }
        if (GunTabType.RPG.name().toLowerCase(Locale.ENGLISH).equals(type)) {
            return 20.0;
        }
        return 16.0;
    }

    @Override
    public boolean canFire(NumenPlayer shooter, ItemStack gun) {
        return Tacz.canFireGun(shooter, gun);
    }

    @Override
    public String tickShoot(NumenPlayer shooter, ItemStack gun, LivingEntity target) {
        ItemStack hand = shooter.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(hand);
        if (iGun == null) {
            return "not_gun";
        }
        String gunId = iGun.getGunId(hand).toString();
        // 弹匣里又有弹了,就重新武装这把枪的换弹判据
        if (iGun.getCurrentAmmoCount(hand) > 0) {
            reloadTried.remove(shooter.getId());
        }
        IGunOperator op = IGunOperator.fromLivingEntity(shooter);
        ShooterDataHolder data = op.getDataHolder();
        // 手换了(手上不是登记的那把):刷新登记;还没登记过就切枪
        if (data.currentGunItem != null && data.currentGunItem.get() != hand) {
            op.initialData();
        }
        if (data.currentGunItem == null) {
            op.draw(shooter::getMainHandItem);
            return null;
        }
        // 枪自己忙着:换弹/拉栓/切枪/冷却——等它的 tick 推进,下一拍再问
        if (data.reloadStateType.isReloading() || data.isBolting) {
            return null;
        }
        if (op.getSynDrawCoolDown() != 0 || op.getSynShootCoolDown() > 0) {
            return null;
        }
        // 疾跑中开不了枪:站定
        if (data.sprintTimeS > 0) {
            shooter.setSprinting(false);
            return null;
        }
        // 狙击枪:开镜打才有准头(女仆的同款判据)
        if (Tacz.isSniper(hand) && !op.getSynIsAiming()) {
            op.aim(true);
            return null;
        }
        if (op.getSynIsAiming() && op.getSynAimingProgress() < 1f) {
            return null;   // 开镜进度没走满,再等
        }
        // 开火这一拍才转身:身体、头、视线全对齐目标,枪口朝向和子弹方向一致
        shooter.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
        ShootResult shot = Tacz.shoot(shooter, target);
        switch (shot) {
            case SUCCESS -> {
                reloadTried.remove(shooter.getId());
                return "fired";
            }
            case NEED_BOLT -> {
                // 手动枪膛空、弹匣有弹:拉栓顶上膛
                op.bolt();
                return null;
            }
            case NO_AMMO -> {
                if (gunId.equals(reloadTried.get(shooter.getId()))) {
                    // 这把枪换过一次弹还是没弹:真的没有匹配弹药,报出去让战斗换枪或收场
                    return "no_ammo for " + gunId;
                }
                reloadTried.put(shooter.getId(), gunId);
                // 换弹只会"开始",换没换完由下一拍的 reloadStateType 看;找不到弹药就换不起来
                op.reload();
                return null;
            }
            case NOT_DRAW -> {
                op.draw(shooter::getMainHandItem);
                return null;
            }
            case OVERHEATED -> {
                return "overheated";
            }
            case ID_NOT_EXIST -> {
                return "id_not_exist";
            }
            default -> {
                // COOL_DOWN / IS_* 之类:状态还没转过来,等下一拍
                return null;
            }
        }
    }
}
