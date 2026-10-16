package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.combat.MeleeWeapon;
import com.dwinovo.numen.entity.NumenPlayer;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * 拔刀剑的近战评分——让它在 {@code Loadout} 挑近战武器时不输给铁剑这类「属性即伤害」的武器。
 *
 * <p>拔刀剑的真实伤害大头在「评价等级 / 拔刀伤害系数 / 倍率」这些面板读不到的东西里,但那些加成只有
 * 1.21.x 的 {@code AttackHelper} 才抽出来成了独立方法(1.1.23 没有)。这里退一步,只用刀本身确定有的
 * {@link ISlashBladeState#getBaseAttackModifier}(基础攻击力)来报分——它至少和铁剑的 6 点同量纲,足够在
 * 排序里把一把像样的拔刀剑排到铁剑前面。要精确的「评分等级×倍率」加成,等迁到有 {@code AttackHelper}
 * 的版本再补。
 *
 * <p>分值只用于几把近战武器之间排序,不是这一击的真实伤害。
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
        return weapon.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> (double) state.getBaseAttackModifier())
                .orElse(0.0);
    }
}
