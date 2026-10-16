package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主手是拔刀剑时接管近战挥击。<b>直接释放剑技,不搓连段、不 fall-through 到原版平A</b>。
 *
 * <h2>为什么「一直在平A」</h2>
 * core 的 {@code AttackCompanionTask.tickWeapon} 每刻(在够得着的距离内)都问一次 {@link #strike}。上一版
 * 里有 40 刻的冷却,冷却期内走平A兜底——于是 40 刻里 39 刻都在平A,只有 1 刻试着放剑技,看起来就是
 * 「AI 一直在平A、放不出剑技」。治本:剑技放不出的那一刻也别退回平A,而是<b>直接切到那一招的 combo</b>
 * ({@code updateComboSeq} 进场即触发 {@code clickAction} 放第一击),后续多段由 {@link SlashBladeTicker} 逐刻
 * 播完。真正放不出(断刀/封印/未附魔)才平A兜底,而且这种情况本身极少。
 *
 * <h2>不去碰走位</h2>
 * 走位(站位环)是 core 的事,{@code MeleeStyle} 只管「这一下怎么挥」。这里只负责:面向目标 + 放剑技。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 两次剑技之间的最小间隔(tick),只用于节流放剑技的频率,不让骄傲之魂瞬间烧光。 */
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

        // 冷却内也直接重放剑技:拔刀剑的 combo 有超时,切同一条 combo 会自然衔接,而不是打断。
        // 这是「直接释放」——不搓连段,每次 strike 都切到目标 combo。
        if (fighter.tickCount - lastArtTick >= ART_COOLDOWN_TICKS) {
            lastArtTick = fighter.tickCount;
            SlashBlade.Art done = SlashBlade.art(fighter, blade, "random", target);
            if (done.executed()) {
                LOG.info("拔刀剑剑技:{} 释放了 {} (目标={})", fighter.getName().getString(), done.sa(), target.getId());
                return true;
            }
            // 放不出:断刀/封印/未附魔。这次先平A兜底,但记下来别再频繁重试。
            lastArtTick = fighter.tickCount;
        } else {
            // 冷却内:用拔刀剑自己的普通挥击(doSlash)保持攻势,而不是原版 player.attack 平A
            // —— 原版平A 的 hurtEnemy 走的是 combo hitEffect,没有 combo 时几乎无伤害。
            ItemStack main = fighter.getMainHandItem();
            main.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
                // 直接切到普通挥击的 combo A1,让拔刀剑自己放刀光
                state.updateComboSeq(fighter, ComboStateRegistry.COMBO_A1.getId());
                fighter.swing(InteractionHand.MAIN_HAND);
            });
            return true;
        }

        // 真放不出(上面 art 失败后才会到这):平A兜底,保证打得出去
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
