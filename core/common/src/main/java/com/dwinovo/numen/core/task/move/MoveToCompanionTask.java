package com.dwinovo.numen.core.task.move;

import java.util.List;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.route.Description;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.Planning;
import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.core.route.Stop;
import com.dwinovo.numen.core.route.Target;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * {@code numen.move.go(plan)} on the companion body: walk a plan from wherever she stands.
 *
 * <h2>计划是承诺</h2>
 * 她看过的计划 = 承诺。站在别处、或世界变了,就从脚下重新规划并比承诺;半路停了也一样。到达由寻路模块决定。
 *
 * <h2>走不到就瞬移</h2>
 * 一段路寻路判 NO_PATH/TERRAIN_BLOCKED,而终点直线距离又远(≥ {@link #TELEPORT_DISTANCE} 格)时,
 * 先试<b>瞬移</b>到终点那一段的落脚点(身体待得住的格子)——不卡进方块、不落虚空。瞬移成功当作这一段到了,
 * 接着下一段;没有落脚点才照老样子解释原因、交给模型。身体的瞬移会报告给模型(见 {@link #teleportNote})。
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;

    /** 一段路走不到、终点直线距离又超过这么远(格),就瞬移送过去。 */
    private static final double TELEPORT_DISTANCE = 32.0;

    private enum Phase {
        PLANNING,
        DRIVING,
        EXPLAINING
    }

    private long leaseCapWork;

    private Phase phase;
    private Plan promise;
    private Planning planning;
    private Plan planned;
    private int leg;
    private BoatNav boat;
    private int stoppedLeg;
    private String stoppedWhy;
    private FailureType stoppedType;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
    }

    @Override
    protected Preparation preparation() {
        promise = r.plan;
        if (atStart()) {
            planned = promise;
            return Preparation.READY;
        }
        Planning again = Planning.of(player, promise.id(), promise.description());
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 不在起点,受理前从脚下重新规划", promise.id());
        return new Preparation() {
            @Override
            public Preparation.Readiness poll() {
                Plan fresh = again.poll();
                if (fresh == null) {
                    return null;
                }
                Blocked blocked = hold(fresh);
                return blocked != null ? Preparation.Readiness.refused(TaskResult.fail(blocked.type().kind(),
                        blocked.why(), blocked.hint(), value())) : Preparation.Readiness.READY;
            }

            @Override
            public void cancel() {
                again.cancel();
            }
        };
    }

    private boolean atStart() {
        return Feet.cell(player).equals(r.plan.from());
    }

    @Override
    protected void onStart() {
        promise = r.plan;
        extendDeadline();
        if (planned != null) {
            setOff();
            return;
        }
        if (atStart()) {
            planned = promise;
            setOff();
            return;
        }
        planning = Planning.of(player, promise.id(), promise.description());
        phase = Phase.PLANNING;
    }

    private void extendDeadline() {
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapWork = workTicks() + CHECK_IN_CAP_TICKS;
    }

    @Override
    protected TaskState onTick() {
        return switch (phase) {
            case PLANNING -> {
                awaitSearch();
                Plan fresh = planning.poll();
                if (fresh == null) {
                    yield TaskState.RUNNING;
                }
                planning = null;
                Blocked blocked = hold(fresh);
                yield blocked != null ? end(blocked.why(), blocked.type(), blocked.hint()) : setOff();
            }
            case DRIVING -> drive();
            case EXPLAINING -> {
                awaitSearch();
                Plan fresh = planning.poll();
                yield fresh == null ? TaskState.RUNNING : explained(fresh);
            }
        };
    }

    private record Blocked(String why, FailureType type, String hint) {}

    private Blocked hold(Plan fresh) {
        int bad = fresh.unreachable();
        if (bad >= 0) {
            return new Blocked("blocked on " + legName(bad) + ": " + fresh.legs().get(bad).why() + ".",
                    FailureType.NO_PATH, null);
        }
        Plan.Difference diff = fresh.beyond(promise);
        if (!diff.isEmpty()) {
            return new Blocked("the way from here goes beyond the plan (made from " + Listing.coords(promise.from())
                    + "), so I did not set off: it would also " + RouteText.beyond(diff) + ". numen.route.plan plans it "
                    + "from here and shows it; then numen.move.go keeps to that plan.", FailureType.TERRAIN_BLOCKED,
                    "numen.move.go(numen.route.plan(plan.spec))");
        }
        planned = fresh;
        return null;
    }

    private TaskState setOff() {
        leg = 0;
        return startLeg();
    }

    private TaskState startLeg() {
        if (leg >= planned.legs().size()) {
            return TaskState.SUCCESS;
        }
        Plan.Leg target = planned.legs().get(leg);
        phase = Phase.DRIVING;
        if (target.chart() != null || planned.description().mode() == Description.Mode.BOAT) {
            BoatNav.Chart chart = target.chart() != null ? target.chart() : chart(target);
            boat = new BoatNav(player, chart);
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 第 {} 段驾船 {} 个航点", planned.id(),
                    leg + 1, chart.path().size());
            return TaskState.RUNNING;
        }
        RouteSpec spec = promise.bind(target.spec());
        Goal goal = target.to().goal();
        nav = target.route() == null ? Trip.to(player, goal, spec, target.to().toward())
                : Trip.following(player, goal, spec, target.route(), target.to().toward());
        nav.spending(planned.description().materials());
        if (target.stop().through()) {
            nav.passing();
        }
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] go {} 第 {} 段{}{} dig={} place={} consent={}",
                planned.id(), leg + 1, target.reach() == Plan.Reach.REACHED ? "" : "(计划只看清一截或没规划,边走边算)",
                target.stop().through() ? "(路过)" : "", spec.dig(), spec.place(), spec.consent());
        return TaskState.RUNNING;
    }

    private BoatNav.Chart chart(Plan.Leg target) {
        BlockPos from = player.getVehicle() instanceof Boat ship ? ship.blockPosition() : player.blockPosition();
        return BoatNav.chart(player, from, target.to().toward());
    }

    private TaskState drive() {
        if (boat != null) {
            return sail();
        }
        if (nav.progressing()) {
            renewLease();
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                leg++;
                yield startLeg();
            }
            case FAILED -> {
                stoppedLeg = leg;
                stoppedWhy = nav.failReason();
                stoppedType = nav.failType();
                stopNav();
                // 走不到、又远 → 瞬移到终点落脚点,成了当作这一段到了
                if (repDistance() >= TELEPORT_DISTANCE && teleportToDestination()) {
                    leg++;
                    yield startLeg();
                }
                planning = Planning.rest(player, planned, leg);
                phase = Phase.EXPLAINING;
                yield TaskState.RUNNING;
            }
        };
    }

    private TaskState sail() {
        if (boat.progressing()) {
            renewLease();
        }
        BoatNav.Status status = boat.tick();
        if (status == BoatNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String why = boat.failReason();
        boat.stop();
        boat = null;
        if (status == BoatNav.Status.FAILED) {
            return end("stopped sailing on " + legName(leg) + " at " + here(player.blockPosition().getY()) + ": "
                    + why, FailureType.BOXED_IN, null);
        }
        leg++;
        return startLeg();
    }

    private TaskState explained(Plan fresh) {
        planning = null;
        Plan.Difference diff = fresh.beyond(promise);
        if (!diff.isEmpty()) {
            return end("stopped on " + legName(stoppedLeg) + " at " + here(player.blockPosition().getY())
                    + ": the way on from here needs cells outside the plan I keep to — it would "
                    + RouteText.beyond(diff) + ". numen.route.plan plans it from here and shows it; then numen.move.go keeps to "
                    + "that plan.", FailureType.TERRAIN_BLOCKED, "numen.move.go(numen.route.plan(plan.spec))");
        }
        return end("blocked on " + legName(stoppedLeg) + ": got within " + String.format("%.1f", repDistance())
                + " blocks of " + destination().words() + " (now on the ground at y="
                + player.blockPosition().getY() + "). " + stoppedWhy + ".", stoppedType, null);
    }

    /**
     * 瞬移到终点那一段的落脚点。身体待得住才瞬移(悬在虚空、卡进方块就不瞬移)。
     */
    private boolean teleportToDestination() {
        BlockPos toward = toward();
        if (toward == null) {
            return false;
        }
        BlockPos landing = Terrain.of(player).settle(toward);
        if (landing == null || landing.getY() < player.level().getMinBuildHeight()) {
            return false;
        }
        player.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0f;
        com.dwinovo.numen.event.NumenEvents.serverMessage(player,
                "走到 " + toward.getX() + ", " + toward.getY() + ", " + toward.getZ()
                        + " 走不通,瞬移到了终点附近 (" + landing.getX() + ", " + landing.getY() + ", " + landing.getZ() + ")。",
                false, 0, 0);
        return true;
    }

    private TaskState end(String why, FailureType type, String hint) {
        fail(why, type, hint);
        return TaskState.FAILED;
    }

    @Override
    protected String refusedHint(List<ConsentItem> refused) {
        Description d = promise.description();
        Description.Places avoid = d.avoid().plus(refused.stream().map(ConsentItem::pos).toList());
        return "numen.move.go(" + com.dwinovo.numen.sdk.Call.of("numen.route.plan", d.written().avoiding(avoid)) + ")";
    }

    private String legName(int index) {
        int legs = promise.legs().size();
        return legs == 1 ? "the walk" : "leg " + (index + 1) + " of " + legs;
    }

    private void renewLease() {
        if (leaseCapWork <= 0) {
            return;
        }
        long capLeft = leaseCapWork - workTicks();
        r.extendDeadlineTo(player.level().getGameTime() + Math.min(PROGRESS_LEASE_TICKS, capLeft));
    }

    private Stop destination() {
        List<Stop> stops = r.plan.description().stops();
        return stops.get(stops.size() - 1);
    }

    private BlockPos toward() {
        Plan plan = planned != null ? planned : r.plan;
        Plan.Leg last = plan.legs().get(plan.legs().size() - 1);
        return last.to() == null ? null : last.to().toward();
    }

    private double repDistance() {
        BlockPos toward = toward();
        if (toward == null) {
            return 0;
        }
        if (destination().to() instanceof Target.Height height) {
            return Math.abs(player.getY() - height.y());
        }
        if (destination().to() instanceof Target.Column) {
            double dx = toward.getX() + 0.5 - player.getX();
            double dz = toward.getZ() + 0.5 - player.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        return Math.sqrt(player.distanceToSqr(Vec3.atBottomCenterOf(toward)));
    }

    @com.dwinovo.numen.sdk.Doc("Where a walk ended.")
    public record Walked(@com.dwinovo.numen.sdk.Doc("Where you stand now (decimals).") Vec3 pos,
                        @com.dwinovo.numen.sdk.Doc("Blocks from the destination; 0 or so when there.")
                        double distanceLeft) {}

    @Override
    protected Walked value() {
        return new Walked(player.position(), Math.round(repDistance() * 10.0) / 10.0);
    }

    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        Stop d = destination();
        String where = d.to().words();
        String reached = switch (d.arrive()) {
            case AT -> d.to() instanceof Target.Height ? "reached elevation y=" + gy
                    : d.to() instanceof Target.Column c ? "arrived at location x=" + c.x() + " z=" + c.z()
                            + ", standing on the ground at y=" + gy
                    : "reached " + here(gy);
            case NEAR -> "arrived within " + d.range() + " blocks of " + where + ", standing at " + here(gy);
            case AWAY -> "got at least " + d.range() + " blocks away from " + where + ", standing at " + here(gy);
            case USE -> "standing at " + here(gy) + ", with " + used() + " in sight and in reach — use it from here";
            case DIG -> d.to() instanceof Target.Cell c
                    ? "standing at " + here(gy) + ", with the " + NavText.name(player.level().getBlockState(c.pos()))
                            + " at " + Listing.coords(c.pos()) + " within reach — `numen.work.dig(" + com.dwinovo.numen.sdk.LuaCodecs.literal(c.pos())
                            + ")` digs it from here"
                    : "standing at " + here(gy) + ", within reach of " + where + " — `numen.work.dig` digs what is in reach "
                            + "from here";
            case PLACE -> "standing at " + here(gy) + ", with " + where + " within reach to build into";
        };
        return reached + (planned.description().mode() == Description.Mode.BOAT
                ? " in the boat" : "") + ".";
    }

    private String used() {
        Plan.Leg last = planned.legs().get(planned.legs().size() - 1);
        Feet feet = Feet.of(player);
        Goal.Sighting sight = feet == null || last.to() == null ? null : last.to().goal().sight(feet.node().getX(),
                feet.node().getY(), feet.node().getZ(), feet.stance());
        return sight == null ? "one of its blocks" : "the " + NavText.name(player.level().getBlockState(sight.target()))
                + " at " + Listing.coords(sight.target());
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        boolean stalled = nav == null || !nav.progressing();
        return "timed out " + String.format("%.1f", repDistance()) + " blocks from target (now at " + here(gy) + "); "
                + (stalled
                        ? "progress had stopped — likely blocked; plan again from here (numen.route.plan) and look at the "
                                + "way, or add a stop in another direction."
                        : "the journey was still progressing and simply exceeded its check-in budget; plan again from "
                                + "here and go on.");
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target";
    }

    private String here(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        if (planning != null) {
            planning.cancel();
            planning = null;
        }
        if (boat != null) {
            boat.stop();
            boat = null;
        }
    }
}
