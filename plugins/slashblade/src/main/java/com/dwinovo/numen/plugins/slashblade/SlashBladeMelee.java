package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 主手是拔刀剑时接管近战挥击。招式<b>随机</b>从 {@link SlashBlade} 的池里抽(地面连段、挑空、
 * 疾走居合、空中下劈、空中连段、晨星),SA(普通/超SA)按概率穿插,不按固定次序。
 *
 * <p>每一招<b>播够 {@link ComboRhythm#PER_MOVE_TICKS} 拍才换下一招</b>,不是每挥一下就换——
 * 招式切得太勤,刀光/位移根本没播完就被打断。换招由 Numen 自己掌控(直接 {@code updateComboSeq} 切 combo),
 * 不依赖拔刀剑那套帧超时状态机。
 *
 * <p><b>目标在近战范围外 → 疾走居合先接近</b>:由 {@link SlashBlade#advanceCombo} 判——远了就先瞬移前冲,
 * 而不是站在原地空挥。这在随机抽招之上加了「够不着先贴上去」这条,让随机不等于乱打。
 *
 * <p><b>为什么状态挂在身体上、不挂在单例上</b>:这个 {@code MeleeStyle} 由 {@code MeleeStyles.register}
 * 注册一次,是共享的单例。节奏状态如果挂在单例字段上,会像第一版那个 SA 计时器一样,死了复活换新身体后
 * 残留旧值——现在把 {@link ComboRhythm} 存进 {@link NumenPlayer#state},跟着身体走,新身体自带归零的干净节奏。
 */
final class SlashBladeMelee implements MeleeStyle {

    /** 每次该换招时,这一拍放 SA 的概率(其余为随机普通招)。 */
    private static final int SA_CHANCE_DENOMINATOR = 4;   // 1/4

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

        rhythm.advance(fighter.tickCount);

        // 按概率决定这一拍放 SA 还是随机普通招。SA 放不出(断刀/封印/无附魔)就退回普通招。
        if (fighter.getRandom().nextInt(SA_CHANCE_DENOMINATOR) == 0) {
            if (SlashBlade.trySlashArts(fighter, blade, target)) {
                return true;
            }
        }

        if (SlashBlade.advanceCombo(fighter, blade, target, fighter.getRandom())) {
            return true;
        }

        // 兜底:切不动(极少),原版平A。
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /**
     * 一具身体自己的出招节奏:只记「当前这一招从哪一刻开始播」{@code lastTick}。
     * 每招最多播 {@link #PER_MOVE_TICKS} 拍;既没播满、又没结束时先不换招。选什么招由上层随机,
     * 这里不再有固定的步进次序。
     */
    static final class ComboRhythm {
        /** 一招最多播这么久(拍)再允许换下一招。20 拍 = 1 秒,足够刀光/位移播完。 */
        static final int PER_MOVE_TICKS = 20;

        private int lastTick = Integer.MIN_VALUE;

        /** 进入下一招,并以 {@code nowTick} 记为「这一招的起点」。 */
        void advance(int nowTick) {
            lastTick = nowTick;
        }

        /** 当前这一招是否还在播放窗口内(没播满规定拍数)。 */
        boolean isPlaying(int nowTick) {
            return nowTick - lastTick < PER_MOVE_TICKS;
        }
    }
}
