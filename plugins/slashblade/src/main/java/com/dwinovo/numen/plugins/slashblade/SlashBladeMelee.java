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
 * 主手是拔刀剑时接管近战挥击。优先级:
 * <ol>
 *   <li><b>能放超SA 就放超SA</b>,否则放<b>普通 SA</b>({@code art("auto")} 替她决定,不去硬判);</li>
 *   <li><b>SA 都放不出</b>(断刀/封印/未附魔/无 SA)才退回原版平A。</li>
 * </ol>
 *
 * <p>冷却只做节流,不让每 tick 狂放剑技烧资源;关键变化是<b>评分没到 3S 之前也放普通 SA</b>,而不是死等超SA
 * 才动手、平挥空耗。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 两次剑技之间的最小间隔(tick)。20 tick = 1 秒。 */
    private static final int ART_COOLDOWN_TICKS = 20;

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
            // auto:够放超SA 就放超SA,否则放普通 SA;两者都放不出才落到平A。
            SlashBlade.Art done = SlashBlade.art(fighter, blade, "auto", target);
            if (done.executed()) {
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(), done.sa(), target.getId());
                return true;
            }
        }

        // 冷却中 / 放不出:原版平A(主手拔刀剑 → ItemSlashBlade.hurtEnemy → 刀光+伤害)
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
