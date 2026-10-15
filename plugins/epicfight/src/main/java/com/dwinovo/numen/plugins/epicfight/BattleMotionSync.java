package com.dwinovo.numen.plugins.epicfight;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Controls;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.network.server.SPUpdatePlayerInput;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 把同伴的移动输入同步成史诗战斗的 {@code dx}/{@code dz},让客户端播走路/跑步动画。
 *
 * <h2>为什么脚不动、只会平移</h2>
 * 史诗战斗客户端判"在移动"靠 {@code PlayerPatch.dx/dz}(见 {@code AbstractClientPlayerPatch.isMoving})。真玩家的
 * 这两个值由 {@code MixinLocalPlayer} 从键盘 {@code xxa/zza} 填入、经 {@code CPUpdatePlayerInput} 包同步;假玩家没有
 * 客户端发这个包,{@code dx/dz} 恒为 0,客户端就永远播 IDLE——身体在动、脚不动。这里在服务端把同伴的移动键折成
 * {@code dx/dz} 写进它的补丁,并发 {@code SPUpdatePlayerInput} 给所有看得见她的客户端。
 *
 * <p>只在 battle 模式同步:vanilla 模式原版走路动画不依赖这套,枪械也不受影响。
 */
final class BattleMotionSync {

    private BattleMotionSync() {}

    static void watch(NumenApi numen) {
        Set<NumenPlayer> companions = Collections.newSetFromMap(new WeakHashMap<>());
        numen.on(CompanionEvent.SPAWN, companions::add);
        numen.on(CompanionEvent.REMOVE, companions::remove);

        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) {
                return;
            }
            companions.removeIf(her -> her.isRemoved() || !her.isAlive());
            for (NumenPlayer her : companions) {
                sync(her);
            }
        });
    }

    private static void sync(NumenPlayer her) {
        ServerPlayerPatch patch = EpicFight.patchOf(her);
        if (patch == null || !patch.isEpicFightMode()) {
            return;
        }
        // 读移动键(不是实体的 xxa/zza——那两格在 Physics.step 的 doTick 之后就被原版重置了),
        // Controls 对象本身持续保留按键状态。forward = 前后,strafe = 左右,与 CPUpdatePlayerInput 的语义一致。
        Controls c = her.controls();
        float forward = (c.held(Controls.Key.FORWARD) ? 1 : 0) - (c.held(Controls.Key.BACK) ? 1 : 0);
        float strafe = (c.held(Controls.Key.LEFT) ? 1 : 0) - (c.held(Controls.Key.RIGHT) ? 1 : 0);
        patch.dx = strafe;
        patch.dz = forward;
        EpicFightNetworkManager.sendToAllPlayerTrackingThisEntity(
                new SPUpdatePlayerInput(her.getId(), forward, strafe), her);
    }
}
