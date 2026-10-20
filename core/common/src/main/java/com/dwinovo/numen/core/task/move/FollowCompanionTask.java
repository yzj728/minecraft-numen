package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Survey;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.nav.ThrowawayBlocks;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 跟着走一阵。默认跟主人,点名了就跟那一只;到点({@link FollowTaskRecord#forTicks})就以成功收场,睡着的也醒来收这个尾
 * ({@link #canRun})。
 *
 * <h2>跟到了就休眠,不是结束</h2>
 * 跟到了(这一趟的目标自己说到了,{@link Goals#within} 落脚点附近 {@code keepWithin} 格)之后
 * {@link #canRun} 返 false:身体让给别人(她可以站着看你、可以被反射拿去吃东西),主人一走远
 * 它自己就醒过来。这跟原版 {@code Goal.canUse()} 是同一个道理——<b>休眠不是失败</b>,不发结果、
 * 不腾槽、不惊动模型。到没到只看目标,这里不另拿距离判"差不多到了";离主人多远只决定要不要起步。
 *
 * <h2>够不着就瞬移</h2>
 * 跟的目标(主人、或点名的那只)离她远到难以走不到时(<b>直线距离超过 {@link #TELEPORT_DISTANCE}</b>,或寻路判
 * NO_PATH/BOXED_IN),先<br>瞬移</b>到它附近的落脚点——不卡进方块、不落进虚空。瞬移成功就视为跟上;
 * 没有落脚点可去才照老样子报「跟不上」,把原因交给模型。
 *
 * <h2>目标没了,主人和别人不一样</h2>
 * <b>主人下线是暂时的</b>——他会回来,所以休眠等着,到点为止。而点名跟的
 * 那只羊死了、或者走出加载范围被卸载了,再等也不会回来:那时收尾报给模型,让它决定下
 * 一步。一套逻辑通吃的话,要么她对着一只死羊站到天荒地老,要么主人一下线任务就没了。
 *
 * <p><b>{@code nav.tick()} 的返回值一个都不能丢</b>:{@link Trip} 收场之后就稳定地是那个结局,
 * 不接住就是她永久定在原地而 {@code task status} 照说"执行中"——主人完全看不出她卡住了。
 * 这里接住的方式就是把它变成任务的结果。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    /** 跟到之后,他比 {@code keepWithin} 多走出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;
    /** 在走的这一趟朝着的落脚点;他挪出它 {@link #RETARGET_DISTANCE} 格外才换目标。 */
    private BlockPos heading;

    /** 他挪出上次的落脚点这么远(格)才把新目标交给在走的这一趟。 */
    private static final double RETARGET_DISTANCE = 2.0;

    /** 跟不上了、直线距离又超过这么远,就瞬移送过去,而不是走断一条又一条的路。32 格:走路 30 秒起步,瞬移更干脆。 */
    private static final double TELEPORT_DISTANCE = 32.0;

    /** 受理之前的准备规划到的那条路:第一趟照它走;没有、或已经用过为 null。 */
    private Route seed;
    /** 到点的那一刻(游戏刻);开工之前是 -1。 */
    private long endsAt = -1;

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (timeUp()) {
            return true;
        }
        Entity target = target(companion);
        if (target == null) {
            return r.target != null;
        }
        if (moving) {
            return true;
        }
        return companion.position().distanceTo(target.position()) > r.keepWithin + RESUME_MARGIN;
    }

    @Override
    protected Preparation preparation() {
        Entity target = target(player);
        if (target == null || !canRun(player)) {
            return Preparation.READY;
        }
        BlockPos anchor = anchor(target);
        Survey survey = Survey.of(player, List.of(new Survey.Leg(goal(anchor), TERRAIN)), ThrowawayBlocks.factory());
        return new Preparation() {
            @Override
            public Preparation.Readiness poll() {
                List<Survey.Found> found = survey.poll();
                if (found == null) {
                    return null;
                }
                Survey.Found leg = found.get(0);
                if (leg.reached()) {
                    seed = leg.route();
                    return Preparation.Readiness.READY;
                }
                return Preparation.Readiness.refused(TaskResult.fail(com.dwinovo.numen.agent.script.ErrorKind.NO_PATH,
                        "can't keep up: " + NavText.failure(leg.outcome(), player, Feet.cell(player), anchor, TERRAIN,
                                ThrowawayBlocks.factory()),
                        null, value()));
            }

            @Override
            public void cancel() {
                survey.cancel();
            }
        };
    }

    @Override
    protected void onStart() {
        moving = false;
        if (endsAt < 0) {
            endsAt = player.level().getGameTime() + r.forTicks;
        }
    }

    /** 到点了。 */
    private boolean timeUp() {
        return endsAt >= 0 && player.level().getGameTime() >= endsAt;
    }

    @Override
    protected TaskState onTick() {
        if (timeUp()) {
            stopNav();
            return TaskState.SUCCESS;
        }
        Entity target = target(player);
        if (target == null) {
            if (r.target == null) {
                return TaskState.RUNNING;
            }
            stopNav();
            fail("the entity you were following is gone (killed, or it left the loaded area)",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        BlockPos anchor = anchor(target);
        if (nav == null) {
            heading = anchor;
            nav = Trip.prepared(player, goal(anchor), TERRAIN, seed, anchor);
            seed = null;
        } else if (anchor.distSqr(heading) > RETARGET_DISTANCE * RETARGET_DISTANCE) {
            heading = anchor;
            nav.retarget(goal(anchor), anchor);
        }
        moving = true;
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                moving = false;
            }
            case FAILED -> {
                stopNav();
                // 跟不上了:先试瞬移送过去,成了就当作跟上;没落脚点才照老样子报。
                if (player.position().distanceTo(target.position()) > TELEPORT_DISTANCE
                        && teleportToAnchor(target, anchor)) {
                    moving = false;
                    return TaskState.RUNNING;
                }
                String why = nav.failReason();
                FailureType type = nav.failType();
                fail("can't keep up: " + why, type);
                return TaskState.FAILED;
            }
        }
        return TaskState.RUNNING;
    }

    /**
     * 瞬移到跟的落脚点:目标那一列里身体待得住的格子。成功返回 true;那一列待不住(悬在虚空)返回 false。
     */
    private boolean teleportToAnchor(Entity target, BlockPos anchor) {
        BlockPos landing = Terrain.of(player).settle(target.blockPosition());
        if (landing == null || landing.getY() < player.level().getMinBuildHeight()) {
            return false;
        }
        player.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0f;
        com.dwinovo.numen.event.NumenEvents.serverMessage(player,
                "太远/走不到,瞬移到了目标附近 (" + landing.getX() + ", " + landing.getY() + ", " + landing.getZ() + ")。",
                false, 0, 0);
        return true;
    }

    /** 跟着谁。没点名就是主人;点名了就按 UUID 现查。 */
    private Entity target(NumenPlayer companion) {
        if (r.target == null) {
            var owner = companion.resolveOwnerPlayer();
            return owner == null || owner.level() != companion.level() ? null : owner;
        }
        Entity e = ((ServerLevel) companion.level()).getEntity(r.target);
        return e == null || e.isRemoved() ? null : e;
    }

    /** 跟到落脚点 {@code anchor} 附近 {@code keepWithin} 格内。 */
    private Goal goal(BlockPos anchor) {
        return Goals.within(Goals.at(anchor), 0, r.keepWithin);
    }

    private static final RouteSpec TERRAIN = RouteSpec.defaults();

    private BlockPos anchor(Entity target) {
        return Terrain.of(player).settle(target.blockPosition());
    }

    @com.dwinovo.numen.sdk.Doc("Where a follow ended.")
    public record Followed(@com.dwinovo.numen.sdk.Doc("Where you stand at the end.") Vec3 pos) {}

    @Override
    protected Followed value() {
        return new Followed(player.position());
    }

    @Override
    protected String successMessage() {
        return "followed " + r.who() + " for " + r.forTicks / 20 + " s";
    }
}
