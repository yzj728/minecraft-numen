package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.TaskFactory;
import net.minecraftforge.eventbus.api.IEventBus;

import java.nio.file.Path;

/**
 * TACZ 联动:让同伴用上 TACZ 枪械——拿枪、朝敌人瞄准开枪、拉栓、换弹、切开火模式、开镜。
 *
 * <p>它本质是一个独立联动模组,只是被内嵌进成品 jar 一起发。所以它<b>不是</b> {@code @Mod} 入口——装没装
 * TACZ 由 {@code Builtin} 那道闸判断,判断为真才调 {@link #install}。TACZ 不在的话,这个类<b>一次都不会被加载</b>,
 * 而这一点是必须的:本联动直接编译依赖 TACZ 的类({@code IGunOperator}、{@code TimelessAPI}),类加载了就会去找那些类。
 *
 * <p>和车万女仆联动不同的地方:车万女仆要接客户端渲染与 tick 事件才有用,而枪械的第三人称渲染 TACZ 自己按"手持枪的实体"
 * 处理,本联动在服务端驱动假玩家开枪即可,不需要挂任何 Forge 事件。{@code modBus} 因而用不上,只为和 {@code Builtin} 的
 * 接线形状保持一致而保留。
 */
public final class NumenTacz {

    /** 这个联动在她的 API 里的名字空间:{@code tacz.gun.*}。 */
    static final String NAMESPACE = "tacz";

    private NumenTacz() {}

    /** 由 {@code Builtin} 在确认 TACZ 在场后调用。 */
    public static void install(IEventBus modBus, Path skillsRoot) {
        NumenPlugins.register(NAMESPACE, numen -> {
            TaczApi.install(numen);
            // tacz.gun.shoot 派下来的开火短活由谁来跑:任务里自动拉栓、换弹、等冷却
            TaskFactory.register(ShootRecord.class, (player, record) -> new ShootTask(record));
            if (skillsRoot != null) {
                numen.bundleSkills(skillsRoot);
            }
        });
    }
}
