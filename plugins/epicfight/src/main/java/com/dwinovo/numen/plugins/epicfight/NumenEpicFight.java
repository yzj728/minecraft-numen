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
 * <p>除了一套 {@code epicfight.fight.*} 工具外,还挂两样:一个 {@link BattleReflex}(战斗模式下有怪近身就自动
 * 举武器格挡、挨打自动翻滚,和本体的原版盾逻辑同一套形状),以及一个 {@link EpicFightMelee}(把战斗模式下的
 * 近战挥击接管到史诗战斗的 {@code BASIC_ATTACK} 技能,让 numen.fight.attack 与自卫本能都有真正的挥砍动画)。
 * 女仆是 NPC 靠 AI 目标做这些,同伴是假玩家,只能经技能容器驱动——见 {@link BattleReflex}。
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
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
