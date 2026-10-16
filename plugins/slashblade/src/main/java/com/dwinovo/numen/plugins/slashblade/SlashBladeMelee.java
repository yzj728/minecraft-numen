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
 *   <li><b>优先搓招</b>:先尝试放一次剑技(SA),放出来就是完整一招(动作+特效+伤害);</li>
 *   <li><b>平 A 兜底</b>:放不出(断刀/封印/不支持/冷却中)就退回 numen 自己那套原版平 A
 *       ({@code player.attack + player.swing}),保证她永远打得出去、动作特效不丢。</li>
 * </ol>
 *
 * <p>之前版本在这里直接调 {@code AttackManager.doSlash},反而把 numen 本来的平 A 链(
 * {@code Player.attack → ItemSlashBlade.hurtEnemy → combo hitEffect})替换成了更简陋的一刀,导致平 A 动作特效
 * 都没了。现在不再替换:平 A 照旧走原版,只在冷却窗口到了、能放出剑技时,用剑技这一招顶替那一下平 A。
 *
 * <p><b>冷却</b>:剑技耗骄傲之魂等资源,不能每一下平 A 都换成剑技。放一次剑技后进 {@link #ART_COOLDOWN_TICKS}
 * 冷却,期间照旧平 A。冷却用身体的 {@code tickCount},不占任务槽、也不依赖额外计时器。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /**
     * 两次剑技之间的最小间隔(tick)。20 tick = 1 秒;40 tick = 2 秒。
     * 太短会疯狂刷判断斩、耗光骄傲之魂;太长则“优先搓招”名存实亡。
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

        // 优先搓招:冷却到了、且刀状态允许就放一次剑技(完整一招)
        if (fighter.tickCount - lastArtTick >= ART_COOLDOWN_TICKS) {
            SlashBlade.Art done = SlashBlade.art(fighter, blade, "normal");
            if (done.executed()) {
                lastArtTick = fighter.tickCount;
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(), done.sa(), target.getId());
                return true;
            }
            // 放不出(断刀/封印/不支持)也记一次冷却,避免每 tick 都徒劳重试
            lastArtTick = fighter.tickCount;
        }

        // 平 A 兜底:照 numen 原版那套挥击,动作特效由 Player.attack → ItemSlashBlade.hurtEnemy 正常产生
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
