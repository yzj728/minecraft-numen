package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.combat.MeleeStyles;
import com.dwinovo.numen.api.combat.MeleeWeapons;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * 拔刀剑(SlashBlade:Resharped)联动:让同伴认出拔刀剑、读它的状态、用它的攻击管线挥刀、放剑技(SA/超SA/招式)。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b> {@code @Mod} 入口——装没装
 * 拔刀剑由 {@code Builtin} 那道闸判断,判断为真才调 {@link #install}。拔刀剑不在的话,这个类<b>一次
 * 都不会被加载</b>,而这一点是必须的:本联动直接编译依赖它的类({@code ItemSlashBlade}、
 * {@code AttackManager}、{@code SuperSlashArts}),类加载了就会去找那些类。
 *
 * <p>挂两样:<b>{@link SlashBladeMelee}</b>(主手是拔刀剑时,近战挥击接管成走拔刀剑自己的攻击管线,冷却到放
 * 剑技、冷却中照旧平A)、<b>{@link SlashBladeWeapon}</b>(让拔刀剑按真实伤害公式报近战评分,赢过铁剑)。
 * 剑技与招式全部直接生成刀光实体({@code AttackManager.doSlash}),不依赖 ComboState 状态机,所以无需逐刻驱动的 ticker。
 * 和史诗战斗({@code PlayerPatch} 那套)是两条完全不同的路,本联动不碰它。
 */
public final class NumenSlashBlade {

    /** 这个联动在她的 API 里的名字空间:{@code slashblade.blade.*}。 */
    static final String NAMESPACE = "slashblade";

    private NumenSlashBlade() {}

    /** 由 {@code Builtin} 在确认拔刀剑在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        // 近战动作进战斗系统:core 的 AttackCompanionTask 挥击那一刻问到这里(和史诗战斗走同一扇门)
        MeleeStyles.register(new SlashBladeMelee());
        // 近战武器评分进 Loadout:拔刀剑按真实伤害报分,不被铁剑这类"属性即伤害"的武器比下去
        MeleeWeapons.register(new SlashBladeWeapon());
        NumenPlugins.register(NAMESPACE, numen -> {
            SlashBladeApi.install(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
