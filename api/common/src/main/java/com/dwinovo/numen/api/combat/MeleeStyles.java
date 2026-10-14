package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 近战动作的登记处:近战动作模组(史诗战斗这类)在初始化时 {@link #register},Numen 的战斗系统
 * ({@code AttackCompanionTask})在挥击那一刻问这里。
 *
 * <p>空表时一切照旧:不认得任何近战动作,行为和不装那些模组时一模一样。所以这里可以无条件被核心引用,
 * 有没有那些模组都不影响启动。
 */
public final class MeleeStyles {

    private static final List<MeleeStyle> STYLES = new CopyOnWriteArrayList<>();

    private MeleeStyles() {}

    /** 近战动作模组登记它的一套打法。重复登记安全;传 null 忽略。 */
    public static void register(MeleeStyle style) {
        if (style != null) {
            STYLES.add(style);
        }
    }

    /** 这一刻该接管这次挥击的那位;没有是 null。 */
    public static MeleeStyle of(NumenPlayer fighter, LivingEntity target) {
        for (MeleeStyle style : STYLES) {
            if (style.applies(fighter, target)) {
                return style;
            }
        }
        return null;
    }
}
