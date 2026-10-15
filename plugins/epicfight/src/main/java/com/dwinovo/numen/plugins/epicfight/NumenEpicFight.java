package com.dwinovo.numen.plugins.epicfight;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.combat.MeleeStyles;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * Epic Fight(史诗战斗)联动:让同伴认得史诗战斗的武器、在普通模式与战斗模式之间切换、用它的动作系统
 * 挥剑连击、翻滚躲闪、举武器格挡。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b> {@code @Mod} 入口——装没装
 * Epic Fight 由 {@code Builtin} 那道闸判断,判断为真才调 {@link #install}。Epic Fight 不在的话,这个类<b>一次
 * 都不会被加载</b>,而这一点是必须的:本联动直接编译依赖它的类({@code PlayerPatch}、{@code CapabilityItem}),
 * 类加载了就会去找那些类。
 *
 * <p>同伴是 {@code ServerPlayer} 假玩家,Epic Fight 按 {@code EntityType.PLAYER} 自动给它挂
 * {@code ServerPlayerPatch},本联动只在服务端驱动它,不需要挂任何 Forge 事件——模式切换、动作执行的同步包
 * 都由 Epic Fight 自己的网络层发给看得见她的客户端。{@code modBus} 因而用不上,只为和 {@code Builtin} 的
 * 接线形状保持一致而保留。
 *
 * <p>除了一套 {@code epicfight.fight.*} 工具外,还挂三样:<b>{@link EpicFightMelee}</b>(把战斗模式下的近战挥击
 * 接管成直接放攻击动画)、<b>{@link BattleReflex}</b>(近身自动格挡、挨打自动翻滚)、<b>{@link BattleMotionSync}</b>
 * (把移动输入同步成 dx/dz,让客户端播走路动画)。女仆是 NPC 靠 AI 目标做这些,同伴是假玩家,只能经补丁直接驱动。
 */
public final class NumenEpicFight {

    /** 这个联动在她的 API 里的名字空间:{@code epicfight.fight.*}。 */
    static final String NAMESPACE = "epicfight";

    private NumenEpicFight() {}

    /** 由 {@code Builtin} 在确认 Epic Fight 在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        // 近战动作进战斗系统:core 的 AttackCompanionTask 挥击那一刻问到这里(和枪械走同一扇门)
        MeleeStyles.register(new EpicFightMelee());
        NumenPlugins.register(NAMESPACE, numen -> {
            EpicFightApi.install(numen);
            BattleReflex.watch(numen);
            BattleMotionSync.watch(numen);
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
