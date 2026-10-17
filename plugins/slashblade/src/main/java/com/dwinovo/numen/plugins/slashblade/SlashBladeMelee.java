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
 *   <li><b>能放超SA 就放超SA</b>(妖+锐利边缘+满耐久+地面+评价 S,{@code art("super")});</li>
 *   <li><b>否则放普通 SA</b>({@code art("normal")},评分没到 3S 前也照放普通 SA,而不是平挥);</li>
 *   <li><b>普通 SA 也放不出</b>(断刀/封印/未附魔/无 SA)才退回原版平A。</li>
 * </ol>
 *
 * <p>冷却只做「节流」,不让每 tick 狂放剑技烧骄傲之魂;但保证「够格就优先放 SA」,尤其评分没到之前
 * 也要放<b>普通 SA</b>,而不是死等 3S 才动手。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 两次剑技之间的最小间隔(tick)。20 tick = 1 秒,足够节流又不会让她显得迟钝。 */
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
            // 先试超SA:够格(妖+锐利边缘+满耐久+评价 S)就放超SA,否则放普通 SA。
            String tryType = SlashBlade.canSuper(blade, fighter) ? "super" : "normal";
            SlashBlade.Art done = SlashBlade.art(fighter, blade, tryType, target);
            if (done.executed()) {
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(),
                        tryType.equals("super") ? "super(" + done.sa() + ")" : done.sa(), target.getId());
                return true;
            }
            // 超SA 不够格时退回来,再明确试一次普通 SA(可能超SA 的妖/锐利判定把普通也挡了):
            if ("super".equals(tryType)) {
                SlashBlade.Art normal = SlashBlade.art(fighter, blade, "normal", target);
                if (normal.executed()) {
                    LOG.info("拔刀剑剑技:{} 释放了普通 {} (目标={})", fighter.getName().getString(),
                            normal.sa(), target.getId());
                    return true;
                }
            }
            // 放不出(断刀/封印/未附魔):落到平A
        }

        // 冷却中 / 放不出:原版平A(主手拔刀剑 → ItemSlashBlade.hurtEnemy → 刀光+伤害)
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
