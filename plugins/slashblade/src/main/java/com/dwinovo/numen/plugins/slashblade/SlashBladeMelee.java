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
 * 主手是拔刀剑时接管近战挥击,照车万女仆「真正的力量」<b>连段流</b>:
 * <ol>
 *   <li>冷却到了 → {@link SlashBlade#trySlashArts} 放一次 SA(评价够则超SA,否则普通 SA);</li>
 *   <li>否则 → {@link SlashBlade#advanceCombo} 推进/起手普通连段(COMBO_A、疾走居合、挑空、空中技都在这自然出现)。</li>
 * </ol>
 *
 * <p>这是「多种招式都能放」的真相:普通剑技是连段本身,SA 是节奏到了额外放的大招,超SA 是评价够时放。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 放 SA 的节奏间隔(tick)。20 tick = 1 秒,期间走普通连段。 */
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

        // 节奏到了就放 SA,否则走普通连段推进——SA 和连段交替,这就是连段流。
        if (fighter.tickCount - lastArtTick >= ART_COOLDOWN_TICKS) {
            if (SlashBlade.trySlashArts(fighter, blade, target)) {
                lastArtTick = fighter.tickCount;
                return true;
            }
            // 放不出(断刀/封印/无 SA)也记一次节奏,避免每 tick 徒劳重试
            lastArtTick = fighter.tickCount;
        }

        if (SlashBlade.advanceCombo(fighter, blade, target)) {
            return true;
        }

        // 兜底:连段推不动(极少),原版平A。
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
