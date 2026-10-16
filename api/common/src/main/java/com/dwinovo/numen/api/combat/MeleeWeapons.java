package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 近战武器评分的登记处:伤害走自己一套公式的近战模组(拔刀剑这类)在初始化时 {@link #register},
 * {@code Loadout} 挑近战武器时问这里,拿不到分行才退回原版攻击属性评分。
 *
 * <p>空表时一切照旧:不认得任何特殊近战武器,行为和没装那些模组时一模一样。所以这里可以无条件被核心引用,
 * 有没有那些模组都不影响启动。
 */
public final class MeleeWeapons {

    private static final List<MeleeWeapon> WEAPONS = new CopyOnWriteArrayList<>();

    private MeleeWeapons() {}

    /** 近战武器模组登记它的一套评分。重复登记安全;传 null 忽略。 */
    public static void register(MeleeWeapon weapon) {
        if (weapon != null) {
            WEAPONS.add(weapon);
        }
    }

    /**
     * 打 {@code target} 时这把武器的近战分值。没被任何登记过的模组认领时返回 0,
     * 由调用方退回原版 {@code WeaponDamage#against} 评分。
     */
    public static double score(NumenPlayer fighter, ItemStack weapon, Entity target) {
        if (weapon == null || weapon.isEmpty()) {
            return 0.0;
        }
        for (MeleeWeapon w : WEAPONS) {
            double s = w.score(fighter, weapon, target);
            if (s > 0.0) {
                return s;
            }
        }
        return 0.0;
    }
}
