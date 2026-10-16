package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import mods.flammpfeil.slashblade.util.TargetSelector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主手是拔刀剑时接管近战挥击,照车万女仆「真正的力量」的 {@code SimpleSlashGoal} 口径:
 * <ol>
 *   <li><b>先看距离</b>:目标在拔刀剑够得到的范围({@code TargetSelector.getResolvedReach})内,才放剑技;</li>
 *   <li><b>够得着就搓招</b>:冷却到了放一次随机档位的预制剑技(面向目标 + post 事件 + 切 combo),完整多段由
 *       {@link SlashBladeTicker} 逐 tick 驱动;</li>
 *   <li><b>够不着就疾走居合冲锋贴脸</b>({@code RAPID_SLASH} 自带向前冲刺位移),而不是退回原版走位环后退——
 *       这正是「边打边退」的治本:拔刀剑近战该贴脸,不是拉后。</li>
 *   <li><b>平A兜底</b>:放不出且冲不上去时,退回 numen 原版平 A,保证打得出去。</li>
 * </ol>
 *
 * <p>选刀不在这里:近战首选哪把由 {@code Loadout} 决定,{@link SlashBladeWeapon} 让拔刀剑按真实伤害报分、赢过
 * 铁剑,身体会自动把它切到主手。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 两次剑技之间的最小间隔(tick)。20 tick = 1 秒;40 tick = 2 秒。 */
    private static final int ART_COOLDOWN_TICKS = 40;

    /** 上一次放剑技是在身体 tickCount 的哪一刻;0 表示还没放过。 */
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
        // 先把身体转向目标、停疾跑,和 numen 平 A 同一套准备动作
        InputDriver.lookAt(fighter, target.getEyePosition());
        fighter.setSprinting(false);

        // 够不着:用疾走居合(自带前冲)贴脸,而不是退回让原版走位环把它拉远
        double reach = TargetSelector.getResolvedReach(fighter);
        if (fighter.distanceTo(target) > reach) {
            return charge(fighter, blade);
        }

        // 优先搓招:冷却到了就放一次随机档位的预制剑技(内部面向目标 + post 事件)
        if (fighter.tickCount - lastArtTick >= ART_COOLDOWN_TICKS) {
            SlashBlade.Art done = SlashBlade.art(fighter, blade, "random", target);
            if (done.executed()) {
                lastArtTick = fighter.tickCount;
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(), done.sa(), target.getId());
                return true;
            }
            lastArtTick = fighter.tickCount;
        }

        // 平 A 兜底
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** 疾走居合冲锋(照车万女仆 {@code SlashBladeAttackUtils.rapidSlashAttack})——前冲贴近目标。 */
    private boolean charge(NumenPlayer fighter, ItemStack blade) {
        return blade.getCapability(mods.flammpfeil.slashblade.item.ItemSlashBlade.BLADESTATE)
                .map(state -> {
                    if (fighter.onGround()) {
                        state.updateComboSeq(fighter, mods.flammpfeil.slashblade.registry.ComboStateRegistry.RAPID_SLASH.getId());
                        fighter.swing(InteractionHand.MAIN_HAND);
                        return true;
                    }
                    return false;
                }).orElse(false);
    }
}
