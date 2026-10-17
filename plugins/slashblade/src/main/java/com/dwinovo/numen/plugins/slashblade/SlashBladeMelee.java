package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主手是拔刀剑时接管近战挥击,照车万女仆「真正的力量」{@code SimpleSlashGoal} 的<b>连段流</b>:
 * <ol>
 *   <li>先 {@code trySlashArts}:当前连段处于「蓄力段/高级蓄力段/快速蓄力段」时,放一次 SA(评价 S 时放超SA);</li>
 *   <li>否则 {@code normalSlashBladeAttack}:普通连段推进(COMBO_A/B/C)、疾走居合、挑空、空中技都在这里自然出现;</li>
 *   <li>放 SA 前补锁定目标 + 拉满评价。</li>
 * </ol>
 *
 * <p>这是车万女仆能「多种招式都能放」的真相:普通剑技是连段本身,SA 是连段走到蓄力段时额外放的大招,
 * 超SA 是评价 S 时在高级蓄力段放。不是「点名调什么招式」,而是「连段自然推进 + 蓄力段判定放 SA」。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

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

        // 连段流:先试 SA(蓄力段才放),否则走普通连段推进。
        boolean swung = SlashBlade.slashBladeFlow(fighter, blade, target);
        if (swung) {
            return true;
        }

        // 兜底:普通连段也推不动(极少),走原版平A。
        fighter.attack(target);
        fighter.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
