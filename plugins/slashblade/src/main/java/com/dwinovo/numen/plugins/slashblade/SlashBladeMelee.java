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
 * 主手是拔刀剑时接管近战挥击,做两件事:
 * <ol>
 *   <li><b>优先搓招</b>:冷却到了就放一次<b>随机档位</b>的剑技(普通/完美/超SA 由服务端随机挑能放的),完整
 *       多段由 {@link SlashBladeTicker} 逐 tick 驱动;</li>
 *   <li><b>平A兜底</b>:放不出(断刀/封印/未附魔/冷却中)就退回 numen 自己那套原版平 A
 *       ({@code player.attack + player.swing}),保证她永远打得出去。</li>
 * </ol>
 *
 * <p>选刀不在这里:近战首选哪把由 {@code Loadout} 决定,{@link SlashBladeWeapon} 让拔刀剑按真实伤害报分、赢过
 * 铁剑,身体会自动把它切到主手——这里只管"主手是拔刀剑时怎么打"。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /**
     * 两次剑技之间的最小间隔(tick)。20 tick = 1 秒;40 tick = 2 秒。
     * 太短会疯狂刷判断斩、耗光骄傲之魂;太长则"优先搓招"名存实亡。
     */
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

        // 优先搓招:冷却到了就放一次随机档位的预制剑技(服务端挑能放的,不必玩家掐蓄力)
        if (fighter.tickCount - lastArtTick >= ART_COOLDOWN_TICKS) {
            SlashBlade.Art done = SlashBlade.art(fighter, blade, "random");
            if (done.executed()) {
                lastArtTick = fighter.tickCount;
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(), done.sa(), target.getId());
                return true;
            }
            // 放不出(断刀/封印/未附魔)也记一次冷却,避免每 tick 都徒劳重试
            lastArtTick = fighter.tickCount;
        }

        // 平 A 兜底:照 numen 原版那套挥击,动作特效由 Player.attack → ItemSlashBlade.hurtEnemy 正常产生
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
