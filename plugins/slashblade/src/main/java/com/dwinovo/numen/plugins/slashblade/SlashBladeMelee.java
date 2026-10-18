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
 *   <li>连段走到<b>可蓄力</b>的档位 → {@link SlashBlade#trySlashArts} 放一次 SA(评价够则超SA,否则普通 SA);</li>
 *   <li>否则 → {@link SlashBlade#advanceCombo} 推进/起手普通连段(COMBO_A、疾走居合、挑空、空中技都在这自然出现)。</li>
 * </ol>
 *
 * <p>这是「多种招式都能放」的真相:普通剑技是连段本身,SA 是节奏到了额外放的大招,超SA 是评价够时放。
 * SA 的释放<b>只由连段当前走到哪个档位决定</b>(可蓄力档位才放),不再靠一个挂在单例上的计时器——
 * 计时器是共享的,复活后新身体 {@code tickCount} 归零、旧值残留,导致复活后 SA 再也放不出来。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

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

        // 可蓄力档位放 SA,否则推进普通连段——SA 穿插在连段之间,这就是连段流。
        if (SlashBlade.trySlashArts(fighter, blade, target)) {
            return true;
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
