package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主手是拔刀剑时的近战挥击<b>直接放拔刀剑的攻击管线</b>(照女仆 {@code SlashBladeCompat} 的
 * {@code AttackManager.doSlash}),不先走原版那一拳。注册进 Numen 的近战登记处({@link MeleeStyles}),
 * core 的 {@code AttackCompanionTask} 在挥击那一刻问到这里,于是模型派的 {@code numen.fight.attack}
 * 与自卫本能开的同一场仗,拿着拔刀剑时打出的都是拔刀剑真正的招式、伤害也走拔刀剑管线。
 *
 * <p>与史诗战斗({@code plugins/epicfight})并行:那是 {@code PlayerPatch} 的动作系统,这是拔刀剑的
 * 攻击管线,两套互不干扰。主手是拔刀剑时本类接管;否则返回 false,原版(或史诗战斗)照旧。
 */
final class SlashBladeMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-slashblade");

    /** 只记一次"接管了挥击":证明这条路径真的走到了。 */
    private boolean takeoverLogged;

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
        // 先把身体转向目标,刀刃沿身体朝向打出;疾跑会带偏出刀方向,先停
        InputDriver.lookAt(fighter, target.getEyePosition());
        fighter.setSprinting(false);
        if (!SlashBlade.swing(fighter, blade)) {
            return false;
        }
        if (!takeoverLogged) {
            takeoverLogged = true;
            LOG.info("拔刀剑近战接管:直接走 AttackManager.doSlash(目标={})", target.getId());
        }
        return true;
    }
}
