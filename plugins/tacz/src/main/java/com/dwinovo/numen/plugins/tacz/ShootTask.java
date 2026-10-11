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
 * <h2>这套状态机从哪来</h2>
 * 车万女仆的 {@code TacInnerCompat.performGunAttack} 是 TACZ 上验证过能打中的完整打法:
 * 瞄准角直指目标、狙击枪先开镜、手动枪膛空拉栓({@code NEED_BOLT})、没子弹换弹({@code NO_AMMO})、
 * 没切枪就切({@code NOT_DRAW})、每步按枪械数据的耗时等足再打。这里逐条照做,只把女仆的
 * "每 N tick 再调一次"换成任务等刻度——同一套逻辑,同一个节拍。
 *
 * <h2>这把枪没弹,换一把接着打</h2>
 * 手上这把枪弹匣空、包里又没有它的匹配弹药时,先看背包里有没有另一把能开火的枪——有就换过去
 * 继续打(喷子打光换狙),不把"没弹药"当最终失败。真的一把都打不了,才失败并说清缺什么。
 *
 * <h2>身体先站定、再转向,然后才扣扳机</h2>
 * 开枪需要疾跑状态清空,而且子弹方向必须和身体朝向一致(玩家看到枪口朝哪,子弹往哪)。
 * 所以每刻先把移动键松开站定、把身体与头转向目标,再问枪的状态、扣扳机。
 *
 * <h2>为什么按住战斗与逃跑两条本能</h2>
 * 同伴身上有 {@code mob_defense}(怪逼近就自动开打,按原版剑/弓逻辑走位,不认识 TACZ 枪)与
 * {@code flee}(判定为空手或扛不住就被追着跑,同样不认识枪)两条反射链,优先级都高于同步短活。
 * 它们一抢身体,开火任务就永远轮不到,枪自然开不出来——"遇敌乱跑、开不了枪"正是它们干的。
 * 派发那一刻(TaczApi.shoot)就已经按住,这里每刻再按一次兜底。任务一结束,空闲时由引擎
 * 统一解除,不用显式还。
 */
final class ShootTask implements Task {

    /** 自卫链的名字(见 core 的 {@code MobDefenseChain.ID});本模块编译期看不见 core,照 id 按住。 */
    private static final String MOB_DEFENSE = "mob_defense";
    /** 逃跑链的名字(见 core 的 {@code FleeChain.ID})。 */
    private static final String FLEE = "flee";

    private final ShootRecord r;
    /** 走到终态时说给模型听的那句话。 */
    private String outcome = "";
    /** 已经对当前手上这把枪试过换弹。再弹 NO_AMMO 就是包里真的没有对应弹药,换枪或判死。 */
    private boolean reloadTried;
    /** 上一拍安排的等待(开镜、拉栓的耗时):到这个游戏刻之前什么也不做。 */
    private long waitUntil = -1;
    /** 最后一次射击的结果码(失败时带回去)。 */
    private String lastResult;

    ShootTask(ShootRecord record) {
        this.r = record;
    }

    @Override
    public TaskState tick(NumenPlayer her) {
        // 开火的这几刻身体归我管:战斗与逃跑本能别来抢(空闲时引擎统一解除)
        her.pauseReflex(MOB_DEFENSE);
        her.pauseReflex(FLEE);

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
        // 枪是直射弹道:看不见目标就是白打,立刻失败并给下一步,别傻等也别对墙空射
        if (!her.hasLineOfSight(living)) {
            outcome = "no line of sight to the target — reposition first: numen.scan.sight finds what blocks the "
                    + "way, numen.move.to walks around it, then shoot again";
            return TaskState.FAILED;
        }
        // 身体先站定(松开一切移动键、停掉疾跑),再转向目标:和 Numen 弓战斗松手前那一下一致
        her.controls().releaseAll();
        her.setSprinting(false);
        Tacz.face(her, living);
        long now = her.serverLevel().getGameTime();
        // 开镜/拉栓的耗时还没走完:等着
        if (now < waitUntil) {
            return TaskState.RUNNING;
        }
        Tacz.Held held = Tacz.held(her, stack);
        // 枪自己还在忙:换弹、拉栓、冷却,都由它自己的 tick 推进,这里只等
        if (held.reloading() || held.bolting() || held.coolDownMs() > 0) {
            return TaskState.RUNNING;
        }
        // 疾跑中开不了枪:站定,下一刻再试
        if (held.sprinting()) {
            return TaskState.RUNNING;
        }
        ShootResult shot = Tacz.shoot(her, living);
        lastResult = shot.name().toLowerCase(java.util.Locale.ROOT);
        switch (shot) {
            case SUCCESS -> {
                // 女仆的打法:狙击枪必须开镜再打——不开镜散布大,基本打不中。这一发已经出膛
                // 就算完成,开镜留给下一次(保持开镜状态,狙击枪连打就一直是准的)。
                if (Tacz.isSniper(stack) && !Tacz.isAiming(her)) {
                    Tacz.aim(her, true);
                    waitUntil = now + Tacz.waitTicks(Tacz.aimTimeSeconds(stack));
                    return TaskState.RUNNING;
                }
                outcome = "fired at " + living.getName().getString();
                return TaskState.SUCCESS;
            }
            case NEED_BOLT -> {
                // 手动枪膛空、弹匣有弹:拉栓顶上膛,按枪的拉栓耗时等足再打
                Tacz.bolt(her);
                waitUntil = now + Tacz.waitTicks(Tacz.boltTimeSeconds(stack));
                return TaskState.RUNNING;
            }
            case NO_AMMO -> {
                if (!reloadTried) {
                    reloadTried = true;
                    // 换弹只会"开始",换没换完由下一刻的 held.reloading 看;换不起来就是没弹药
                    Tacz.reload(her);
                    return TaskState.RUNNING;
                }
                // 这把枪弹匣空、又没有它的匹配弹药:换一把能开火的枪接着打
                String gunId = gun.getGunId(stack).toString();
                String other = Tacz.otherFireableGun(her, gunId);
                if (other != null) {
                    Tacz.equip(her, other);
                    reloadTried = false;
                    waitUntil = now + 10;   // 换枪让几刻切枪动作
                    return TaskState.RUNNING;
                }
                outcome = "no_ammo: the gun " + gunId + " is empty and you carry no matching ammo, and no other "
                        + "gun you carry can fire either — ask your owner for ammo";
                return TaskState.FAILED;
            }
            case NOT_DRAW -> {
                // 枪没登记成当前操作对象:切枪,女仆的同款处理
                Tacz.draw(her);
                return TaskState.RUNNING;
            }
            case COOL_DOWN, IS_RELOADING, IS_BOLTING, IS_SPRINTING, IS_DRAWING, IS_MELEE -> {
                // 状态又变了:等下一刻
                return TaskState.RUNNING;
            }
            case OVERHEATED -> {
                outcome = "the gun is overheated and locked; wait for it to cool down";
                return TaskState.FAILED;
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
