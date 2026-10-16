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
 * 每服务端刻推进同伴当前 combo 的时间线,让拔刀剑的多段刀光 / 位移 / 收刀 / 命中判定按它自己的
 * {@code ComboState.tickAction} 完整放出来。
 *
 * <h2>为什么假玩家放不完整</h2>
 * 拔刀剑的每一招都是一串 {@code ComboState} 状态机链,真玩家靠 {@link mods.flammpfeil.slashblade.item.ItemSlashBlade#inventoryTick}
 * 里 {@code cs.tickAction(living)} 逐 tick 驱动——判断斩的"收刀冲刺 + 连续刀光 + 收刀音效 + 眩晕"整段都靠
 * 这条链一段接一段放。假玩家({@code ServerPlayer})没有那条被动背包 tick,切到目标 combo 后只进场那一下
 * ({@code clickAction}),后面的多段全停了。这里在服务端复现同一条链:每刻对主手持拔刀剑的同伴调
 * {@link SlashBlade#tickAction},让刀自己的时间线跑完。这是照史诗战斗插件 {@code BattleMotionSync} 的路子——
 * 那个同步移动输入,这个推进连段。
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
