package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeWeapon;
import com.dwinovo.numen.entity.NumenPlayer;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.AttackHelper;
import mods.flammpfeil.slashblade.util.AttackManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * 拔刀剑的近战评分——因为它的伤害大头(评分等级 / 拔刀伤害系数 / 倍率)不写在原版
 * {@code ATTACK_DAMAGE} 属性上,只按攻击力排会被铁剑这类"属性即伤害"的武器在纸面上比下去。
 *
 * <p>伤害公式是拔刀剑本人 {@link AttackHelper#calculateTotalDamage} 那条:
 * {@code (面板攻击力 + 横扫 + 评分等级加成 + 杀手附魔) × 连招系数 × 拔刀伤害系数 × 倍率 × 暴击}。
 * 这里只补"原版属性读不到"的那一大块(评分等级加成 × 拔刀伤害系数),面板攻击力已经由
 * {@link ItemSlashBlade#getAttributeModifiers} 注进原版属性、被 {@code WeaponDamage} 读到,不重复算。
 *
 * <p>分值只用于几把近战武器之间排序,量纲对齐原版攻击力(铁剑 6 点),不是这一击的真实伤害。
 */
final class SlashBladeWeapon implements MeleeWeapon {

    @Override
    public double score(NumenPlayer fighter, ItemStack weapon, Entity target) {
        if (!SlashBlade.isSlashBlade(weapon)) {
            return 0.0;
        }
        // 断刀只有 -0.5 的伤害,不认领;让原版评分(读那 -0.5 的属性)去判它,别把它当狠刀
        if (SlashBlade.isBroken(weapon)) {
            return 0.0;
        }
        return weapon.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            // 面板攻击力 = 原版 ATTACK_DAMAGE(已含锻造收益),交给 WeaponDamage 读;这里补上拔刀剑独有的
            // 评分等级加成与拔刀伤害系数(默认 1.0,玩家/装备可改)。乘以这两个就是"这一下大概多狠"的同档分。
            double rankBonus = AttackHelper.getRankBonus(fighter);
            double slasherScale = AttackManager.getSlashBladeDamageScale(fighter);
            // baseAttackModifier 是刀本身的基础攻击力(NBT 里的 baseAttackModifier),作为"这刀有多狠"的底数;
            // 评分等级加成是段位级数的一半(SSS 等高位更多),照拔刀剑自己的公式加进去。
            double base = state.getBaseAttackModifier();
            return Math.max(0.0, base + rankBonus) * slasherScale;
        }).orElse(0.0);
    }
}
