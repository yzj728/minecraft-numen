package com.dwinovo.numen.api.combat;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 枪械的登记处:枪械模组的插件在模组初始化时 {@link #register},Numen 的战斗系统({@code Loadout}、
 * {@code AttackCompanionTask})扫装备、开枪时问这里。
 *
 * <p>空表时一切照旧:不认得任何枪,行为和不装枪械模组时一模一样。所以这里可以无条件被核心引用,
 * 有没有枪械模组都不影响启动。
 */
public final class Firearms {

    private static final List<RangedFirearm> FIREARMS = new CopyOnWriteArrayList<>();

    private Firearms() {}

    /** 枪械插件登记它认得的枪。重复登记安全;传 null 忽略。 */
    public static void register(RangedFirearm firearm) {
        if (firearm != null) {
            FIREARMS.add(firearm);
        }
    }

    /** 这件物品是不是某个登记过的枪械认得的枪。空物品不是。 */
    public static boolean isFirearm(ItemStack stack) {
        return of(stack) != null;
    }

    /** 认这把枪的那位;没有是 null。 */
    public static RangedFirearm of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (RangedFirearm firearm : FIREARMS) {
            if (firearm.isFirearm(stack)) {
                return firearm;
            }
        }
        return null;
    }
}
