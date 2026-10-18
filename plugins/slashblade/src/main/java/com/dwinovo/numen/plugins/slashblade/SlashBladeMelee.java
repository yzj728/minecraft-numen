package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 主手是拔刀剑时接管近战挥击。招式由 {@link ComboRhythm} <b>按身体独立</b>地轮换:
 * <ol>
 *   <li>{@code step%4==1} —— 地面连段 A 起手({@code COMBO_A1});</li>
 *   <li>{@code step%4==2} —— 挑空({@code UPPERSLASH});</li>
 *   <li>{@code step%4==3} —— 疾走居合({@code RAPID_SLASH});</li>
 *   <li>{@code step%4==0} —— 放一次 SA(收尾)。</li>
 * </ol>
 *
 * <p>每一招<b>播够 {@link ComboRhythm#PER_MOVE_TICKS} 拍才换下一招</b>,不是每挥一下就换;
 * 这样普通连段、挑空、疾走居合三种招都能被明确轮到,而不是像网点自动推进那样「挥两下就到底」。
 * 换招由 Numen 自己掌控(直接 {@code updateComboSeq} 切 combo),不依赖拔刀剑那套帧超时状态机。
 *
 * <p><b>为什么状态挂在身体上、不挂在单例上</b>:这个 {@code MeleeStyle} 由 {@code MeleeStyles.register}
 * 注册一次,是共享的单例。招式的步进计数器如果挂在单例字段上,会像上一版那个 SA 计时器一样,
 * 死了复活换新身体后残留旧值——现在把 {@link ComboRhythm} 存进 {@link NumenPlayer#state},跟着身体走,
 * 新身体自带归零的干净节奏。
 */
final class SlashBladeMelee implements MeleeStyle {

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

        ComboRhythm rhythm = fighter.state(ComboRhythm.class, ComboRhythm::new);

        // 上一招既没播满、又没结束 → 这一拍不动,等它播完(招式自身的 tick 驱动刀光/位移)。
        if (rhythm.isPlaying(fighter.tickCount) && SlashBlade.isBusy(fighter, blade)) {
            return true;
        }

        int step = rhythm.nextStep(fighter.tickCount);
        if (step % 4 == 0) {
            // 三个普通招轮完,这一格收尾放一次 SA,放完回第 1 步重开一轮。
            if (SlashBlade.trySlashArts(fighter, blade, target)) {
                rhythm.reset(fighter.tickCount);
                return true;
            }
            // 放不出(断刀/封印/无附魔)——仍当作这一格用掉,避免每拍徒劳重试。
            rhythm.reset(fighter.tickCount);
        }

        // 切到当前步对应的普通招:直接指定 combo,第一击当场打出,后续由 ticker 播完。
        if (SlashBlade.advanceCombo(fighter, blade, target, rhythm.step())) {
            return true;
        }

        // 兜底:切不动(极少),原版平A。
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * 一具身体自己的招式轮换节奏:当前步进 {@code step},以及「当前这一招从哪一刻开始播」{@code lastTick}。
     * 每招最多播 {@link #PER_MOVE_TICKS} 拍;既没播满、又没结束时先不换招。
     */
    static final class ComboRhythm {
        /** 一招最多播这么久(拍)再允许换下一招。20 拍 = 1 秒,足够刀光/位移播完。 */
        static final int PER_MOVE_TICKS = 20;

        private int step = -1;
        private int lastTick = Integer.MIN_VALUE;

        /** 当前这一步的招是否还在播放窗口内(没播满规定拍数)。 */
        boolean isPlaying(int nowTick) {
            return nowTick - lastTick < PER_MOVE_TICKS;
        }

        /** 步进到下一招,并以 {@code nowTick} 记为「本招起点」,返回新步进。 */
        int nextStep(int nowTick) {
            step++;
            lastTick = nowTick;
            return step;
        }

        /** 当前步进(0 起)。 */
        int step() {
            return step;
        }

        /** 回到本轮起点(第 -1 步),下一拍从第 0 步开始。 */
        void reset(int nowTick) {
            step = -1;
            lastTick = nowTick - PER_MOVE_TICKS;   // 立即允许切入第 0 步
        }
    }
}
