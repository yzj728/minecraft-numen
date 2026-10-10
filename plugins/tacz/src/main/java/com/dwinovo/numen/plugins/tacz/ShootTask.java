package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 打出一发:任务每刻把枪的状态机摆对,直到子弹真的出膛,或实在开不了枪。
 *
 * <h2>为什么把循环放进任务而不是让模型自己拉栓换弹</h2>
 * 枪是一个状态机,前置条件不满足时 TACZ 一枪不开、只回一个结果码:手动枪要拉栓({@code NEED_BOLT})、
 * 换弹/拉栓中({@code IS_RELOADING}/{@code IS_BOLTING})、疾跑中({@code IS_SPRINTING})、冷却中
 * ({@code COOL_DOWN})、没子弹({@code NO_AMMO})。这些都要跨好几个游戏刻才能摆平,而模型一次调用
 * 只有程序执行那一下,等不了刻。任务在服务器刻里跑,看见状态没摆对就等下一刻,摆对就扣扳机——
 * "优先开火,不行就自己把枪准备好再打"整条路都替模型走完,模型只拿到最终结果。
 *
 * <p>客户端的自动换弹/自动拉栓对同伴无效(它是没有客户端的服务端假玩家),所以这里补上
 * 服务端的等价物:膛空就拉栓,弹匣空就换弹。自动枪不需要拉栓,{@code NEED_BOLT} 不会出现,
 * 这条循环对它们就是"等冷却然后开枪"。
 */
final class ShootTask implements Task {

    private final ShootRecord r;
    /** 走到终态时说给模型听的那句话。 */
    private String outcome = "";
    /** 已经试过换弹。再弹 NO_AMMO 就是包里真的没有对应弹药,判死,别在换弹和空射之间死循环。 */
    private boolean reloadTried;
    /** 最后一次射击的结果码(失败时带回去)。 */
    private String lastResult;

    ShootTask(ShootRecord record) {
        this.r = record;
    }

    @Override
    public TaskState tick(NumenPlayer her) {
        Entity found = her.serverLevel().getEntity(r.targetId);
        if (!(found instanceof LivingEntity living) || !living.isAlive()) {
            outcome = "the target is gone (died or left) before a shot went off";
            return TaskState.FAILED;
        }
        ItemStack stack = her.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            outcome = "no TACZ gun in your main hand; tacz.gun.equip first";
            return TaskState.FAILED;
        }
        Tacz.Held held = Tacz.held(her, stack);
        // 枪还在准备自己:换弹中、拉栓中、冷却中,都等下一刻再试
        if (held.reloading() || held.bolting() || held.coolDownMs() > 0) {
            return TaskState.RUNNING;
        }
        // 疾跑中开不了枪:站定,下一刻再试
        if (held.sprinting()) {
            her.setSprinting(false);
            return TaskState.RUNNING;
        }
        ShootResult shot = Tacz.shoot(her, living);
        lastResult = shot.name().toLowerCase(java.util.Locale.ROOT);
        switch (shot) {
            case SUCCESS -> {
                outcome = "fired at " + living.getName().getString();
                return TaskState.SUCCESS;
            }
            case NEED_BOLT -> {
                // 手动枪膛空、弹匣有弹:拉栓顶上膛,下一刻再打
                Tacz.bolt(her);
                return TaskState.RUNNING;
            }
            case NO_AMMO -> {
                if (reloadTried) {
                    outcome = "no ammo, and reloading did not feed new rounds — you carry no matching ammo";
                    return TaskState.FAILED;
                }
                reloadTried = true;
                // 换弹只会"开始",换没换完由下一刻的 held.reloading 看;换不起来就是没弹药
                Tacz.reload(her);
                return TaskState.RUNNING;
            }
            case COOL_DOWN, IS_RELOADING, IS_BOLTING, IS_SPRINTING, IS_DRAWING, IS_MELEE -> {
                // 状态又变了:等下一刻
                return TaskState.RUNNING;
            }
            default -> {
                outcome = "could not fire: " + lastResult;
                return TaskState.FAILED;
            }
        }
    }

    /** 不占身体之外的资源,丢了身体没有要收拾的。 */
    @Override
    public void stop(NumenPlayer companion, StopReason why) {}

    @Override
    public TaskResult result(TaskState terminal) {
        return switch (terminal) {
            case SUCCESS -> TaskResult.ok(outcome, new TaczApi.Fired(r.targetId, "success"));
            case TIMEOUT -> TaskResult.timeout("could not get a shot off in time — the gun stayed busy (reloading, "
                    + "bolting or on cool-down) the whole while");
            case CANCELLED -> TaskResult.cancelled("stopped before the shot went off");
            default -> TaskResult.fail(ErrorKind.FAILED, outcome, null,
                    new TaczApi.Fired(r.targetId, lastResult == null ? "failed" : lastResult));
        };
    }

    @Override
    public String name() {
        return r.getToolName();
    }
}
