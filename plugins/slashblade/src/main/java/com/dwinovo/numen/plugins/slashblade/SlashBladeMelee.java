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
 *   <li><b>一套连段打完</b>的下一拍 → {@link SlashBlade#trySlashArts} 放一次 SA(评价够则超SA,否则普通 SA);</li>
 *   <li>否则 → {@link SlashBlade#advanceCombo} 起手/推进地面连段 A(COMBO_A1 → A2 → A3 → A4 → A5)。</li>
 * </ol>
 *
 * <p>这是「先打完一套连招、再放大招」的节奏:普通剑技是连段本身,SA 是每套连段收尾时额外放的大招,
 * 超SA 是评价够时放。SA 的释放<b>只由「上一套连段是否走到终点」决定</b>,不再靠一个挂在单例上的计时器——
 * 计时器是共享的,复活后新身体 {@code tickCount} 归零、旧值残留,会导致复活后 SA 再也放不出来;
 * 也不再靠「停在可蓄力档位」判断——那会撞上起手招,导致 SA 连发、连段不走。
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

        // 上一套连段打完才放 SA,否则推进连段——一套连招收尾一次 SA,这就是想要的节奏。
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
