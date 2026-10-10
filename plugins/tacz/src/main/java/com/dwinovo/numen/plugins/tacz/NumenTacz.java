package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.combat.Firearms;
import com.dwinovo.numen.task.TaskFactory;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * TACZ 联动:让同伴把 TACZ 枪当真正的武器——拿枪、开枪、拉栓、换弹、切开火模式、开镜。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b> {@code @Mod} 入口——装没装
 * TACZ 由 {@code Builtin} 那道闸判断,判断为真才调 {@link #install}。TACZ 不在的话,这个类<b>一次都不会被加载</b>,
 * 而这一点是必须的:本联动直接编译依赖 TACZ 的类({@code IGunOperator}、{@code TimelessAPI}),类加载了就会去找那些类。
 *
 * <p><b>主路是 {@link Firearms} 登记</b>:把枪注册进 Numen 自己的战斗系统之后,{@code numen.fight.attack} 与自卫本能
 * 都会用枪打(走原版那套走位/朝向/站定),逃跑链也不会再把持枪的她当赤手空拳——这解决了"反射抢身体、遇敌乱跑、切近战"。
 * {@code tacz.gun} 那一组是精细控制(看枪、换枪、试枪),不参与抢身体。
 *
 * <p>和车万女仆联动不同的地方:车万女仆要接客户端渲染与 tick 事件才有用,而枪械的第三人称渲染 TACZ 自己按"手持枪的实体"
 * 处理,本联动不需要挂任何 Forge 事件。{@code modBus} 因而用不上,只为和 {@code Builtin} 的接线形状保持一致而保留。
 */
public final class NumenTacz {

    /** 这个联动在她的 API 里的名字空间:{@code tacz.gun.*}。 */
    static final String NAMESPACE = "tacz";

    private NumenTacz() {}

    /** 由 {@code Builtin} 在确认 TACZ 在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        // 枪进战斗系统:Loadout 认它、attack 用它、逃跑链当它是武器
        Firearms.register(new TacFirearm());
        NumenPlugins.register(NAMESPACE, numen -> {
            TaczApi.install(numen);
            // tacz.gun.shoot 派下来的开火短活由谁来跑(精细控制的那条道)
            TaskFactory.register(ShootRecord.class, (player, record) -> new ShootTask(record));
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
