package com.dwinovo.numen.plugins.tacz;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskRecord;

/**
 * {@code tacz.gun.shoot} 派下来的一次射击:打谁,以及这次调用本身。
 *
 * <p>它是一段有界的短活({@link ServerCall#sync} 那一条道):开枪本身是一刻的事,宽出来的
 * 期限是让任务把枪的状态机摆对——手动枪拉栓、没子弹换弹、等冷却与换弹收尾,这些都要
 * 好几个游戏刻才走得完,见 {@link ShootTask}。
 */
final class ShootRecord extends TaskRecord {

    /** 期限(游戏刻):一整套"换弹 + 拉栓 + 开火"最坏十来秒走完,留足余量。 */
    private static final long TIMEOUT_TICKS = 10 * 20;

    /** 目标实体的运行期 id;任务里每刻重新解析,它动也照打。 */
    final int targetId;

    ShootRecord(ServerCall source, int targetId) {
        super(source, source.her().level().getGameTime() + TIMEOUT_TICKS);
        this.targetId = targetId;
    }

    @Override
    public String describe() {
        return getToolName() + " " + targetId;
    }
}
