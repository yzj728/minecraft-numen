package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主手是拔刀剑时接管近战挥击。做两件事:
 * <ol>
 *   <li><b>冷却到了放剑技</b>:生成多段刀光(和平A 同一条 {@code AttackManager.doSlash} 链路),伤害各自结算;</li>
 *   <li><b>冷却中照旧平A</b>:{@code fighter.attack + swing}——主手是拔刀剑时,这条原版平A 走
 *       {@code Player.attack → ItemSlashBlade.hurtEnemy → combo hitEffect},本身就是有效的拔刀剑刀光+伤害
 *       (已验证)。所以冷却中不瞎折腾,交给原版平A。</li>
 * </ol>
 *
 * <p>关键:这里<b>必须</b>在冷却中也返回 true 并自己平A,因为 core 的 {@code AttackCompanionTask.tickWeapon} 一旦
 * 进了 {@code MeleeStyle.strike} 且返回 true,就 {@code return} 不再走它自己的原版平A——若这里冷却中什么都不做
 * 就返回 true,等于把「本来能打的平A」吞掉了,表现就是「既放不出剑技、又不再平A」。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 两次剑技之间的最小间隔(tick)。 */
    private static final int ART_COOLDOWN_TICKS = 30;

    private int lastArtTick;

    @Override
    public boolean applies(NumenPlayer fighter, LivingEntity target) {
        return SlashBlade.isSlashBlade(fighter.getMainHandItem());
    }

    @Override
    public boolean strike(NumenPlayer fighter, LivingEntity target) {
        ItemStack blade = fighter.getMainHandItem();
        if (!SlashBlade.isSlashBlade(blade)) {
            return false;
        }
        InputDriver.lookAt(fighter, target.getEyePosition());
        fighter.setSprinting(false);

        if (fighter.tickCount - lastArtTick >= ART_COOLDOWN_TICKS) {
            lastArtTick = fighter.tickCount;
            SlashBlade.Art done = SlashBlade.art(fighter, blade, "random", target);
            if (done.executed()) {
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(), done.sa(), target.getId());
                return true;
            }
            // 放不出(断刀):落回平A
        }

        // 冷却中 / 放不出:原版平A(主手拔刀剑 → ItemSlashBlade.hurtEnemy → 刀光+伤害)
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
