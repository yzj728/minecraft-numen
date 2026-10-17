package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 每服务端刻推进同伴当前 combo 的时间线,让判断斩这类「tickAction 型」SA 的多段特效完整放出来。
 *
 * <h2>为什么需要它</h2>
 * 拔刀剑的每招都是一个 {@code ComboState} 链:进场 {@code clickAction} 放第一击,后续 {@code tickAction} 放多段
 * (判断斩的「后退 0.9 格 + 召唤次元斩」就在 tickAction 里)。真玩家靠 {@code ItemSlashBlade.inventoryTick} 里的
 * {@code cs.tickAction(living)} 逐刻驱动;假玩家({@code ServerPlayer})没有那条被动链,切 combo 后只停在第一击。
 * 这里在服务端复现同一条链:每刻对主手持拔刀剑的同伴调 {@link SlashBlade#tickAction}。照史诗战斗插件的
 * {@code BattleMotionSync} 路子。
 */
final class SlashBladeTicker {

    private SlashBladeTicker() {}

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
                if (SlashBlade.isSlashBlade(her.getMainHandItem())) {
                    SlashBlade.tickAction(her);
                }
            }
        });
    }
}
