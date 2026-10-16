package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * 一种「近战评分不能只看原版 {@code ATTACK_DAMAGE}」的武器——拔刀剑这类伤害走自己一套公式的模组
 * 武器,把它注册进来(见 {@link MeleeWeapons#register}),{@code Loadout} 挑近战武器时先问这里,没被认领
 * 的才退回读原版攻击属性。
 *
 * <h2>为什么需要它</h2>
 * 原版的近战伤害就是面板攻击力(加附魔)。但有些模组武器(拔刀剑)的面板攻击力只是伤害公式的一小项,
 * 大头在「评分等级 / 倍率 / 连招系数 / 暴击」这些面板上读不到的东西里。只按攻击力排序,这种武器会被
 * 系统性地排在铁剑这类「属性即伤害」的武器后面,于是明明手里是把更狠的刀,身体却去拿铁剑贴脸。
 * 让模组自己报一个「打这个目标有多狠」的分,排序才符合实际。
 *
 * <h2>实现的规矩</h2>
 * <ul>
 *   <li>实现在服务端主线程被调({@code Loadout} 扫背包时);它必须对任何物品安全回答,不抛异常。</li>
 *   <li>{@link #score} 返回 0 表示「我不认得它,照原版评分」;返回正数是这把武器真实的近战分值。</li>
 *   <li>分值只用于在几把近战武器之间排序,不是这一击的真实伤害,量纲要和原版攻击力同档,否则排不准。</li>
 * </ul>
 */
public interface MeleeWeapon {

    /**
     * 这把武器打 {@code target} 的近战分值。0 = 不认领,交给原版 {@code WeaponDamage#against} 评分;
     * 正数 = 按实现自己的公式给的分。
     */
    double score(NumenPlayer fighter, ItemStack weapon, Entity target);
}
