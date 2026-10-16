package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.combat.MeleeStyles;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * 拔刀剑(SlashBlade:Resharped)联动:让同伴认出拔刀剑、读它的状态、用它的攻击管线挥刀、放剑技(SA)。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b> {@code @Mod} 入口——装没装
 * 拔刀剑由 {@code Builtin} 那道闸判断,判断为真才调 {@link #install}。拔刀剑不在的话,这个类<b>一次
 * 都不会被加载</b>,而这一点是必须的:本联动直接编译依赖它的类({@code ItemSlashBlade}、
 * {@code AttackManager}、{@code SlashArts}),类加载了就会去找那些类。
 *
 * <p>普通挥刀照车万女仆的拔刀剑兼容方法({@code AttackManager.doSlash},不经过技能容器、也不经过补丁);
 * 剑技照车万女仆「真正的力量」那类附属的口径({@code getSlashArts().doArts} + {@code PerformSlashArtEvent}
 * + {@code updateComboSeq})。两条口子对任意活物(玩家与 NPC)都一样,同伴是假玩家也能直接用。
 * 和史诗战斗({@code PlayerPatch} 那套)是两条完全不同的路,本联动不碰它。除了一组
 * {@code slashblade.blade.*} 工具外,还挂 {@link SlashBladeMelee}:主手是拔刀剑时,{@code numen.fight.attack}
 * 的近战挥击被接管成真正的拔刀剑招式。
 */
public final class NumenSlashBlade {

    /** 这个联动在她的 API 里的名字空间:{@code slashblade.blade.*}。 */
    static final String NAMESPACE = "slashblade";

    private NumenSlashBlade() {}

    /** 由 {@code Builtin} 在确认拔刀剑在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        // 近战动作进战斗系统:core 的 AttackCompanionTask 挥击那一刻问到这里(和史诗战斗走同一扇门)
        MeleeStyles.register(new SlashBladeMelee());
        NumenPlugins.register(NAMESPACE, numen -> {
            SlashBladeApi.install(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
