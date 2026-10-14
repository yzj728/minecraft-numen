package com.dwinovo.numen.plugins.epicfight;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 史诗战斗的战斗本能:同伴在战斗模式(battle)下,<b>自动</b>举武器格挡、挨打后翻滚躲闪。
 *
 * <h2>和女仆(车万女仆联动)的差别</h2>
 * 女仆是 NPC({@code HumanoidMobPatch}),史诗战斗给它的是 mob 补丁,靠 AI 目标
 * ({@code AnimatedAttackGoal}/{@code TargetChasingGoal})加 {@code CombatBehaviors} 自己决定
 * 出刀、格挡、翻滚。同伴是<b>假玩家</b>({@code ServerPlayerPatch}),史诗战斗给它的是玩家补丁
 * ——玩家的刀、挡、滚<b>没有 AI</b>,只能经技能容器({@code SkillContainer})驱动。所以这里不是挂
 * AI 目标,而是直接驱动它的 {@code GUARD}/{@code ROLL} 两个技能容器。
 *
 * <h2>格挡:有怪逼到近战距离就举,退开就放</h2>
 * 和本体的原版盾逻辑({@code AttackCompanionTask.tickShield})同一套形状:有威胁就挡,没威胁就放。
 * 史诗战斗里格挡挡的是正面近战,箭、爆炸、魔法挡不住——那是史诗战斗自己的规则,照它的来。
 * 举挡不锁出手:{@code BasicAttack} 只查 {@code EntityState.CAN_BASIC_ATTACK},而举挡不改变实体状态,
 * 所以挡着照样能挥刀。
 *
 * <h2>翻滚:挨一下立刻向后滚,带冷却</h2>
 * 只做<b>反应式</b>的:挨打那一刻转向伤害来源、向后滚开拉开距离。没有冷却的话连滚会把体力抽干
 * ({@code status().stamina}),还把自己的攻击窗口全滚没了。
 *
 * <h2>只在 battle 模式</h2>
 * 不在战斗模式时它什么都不做(顺手把还举着的格挡放下),原版盾、原版挥击照旧,不抢任何事。
 */
final class BattleReflex {

    /** 看多远的怪算"身边"。 */
    private static final double SCAN_RADIUS = 8.0;

    /** 两次翻滚之间的最小间隔(刻)。 */
    private static final long DODGE_COOLDOWN_TICKS = 20L;

    /** 原版怪物近战判定往外扩的那一截 {@code Mob.DEFAULT_ATTACK_REACH}(≈0.83)。 */
    private static final double MOB_ATTACK_REACH = Math.sqrt(2.04) - 0.6;

    private BattleReflex() {}

    static void watch(NumenApi numen) {
        Set<NumenPlayer> companions = Collections.newSetFromMap(new WeakHashMap<>());
        Map<NumenPlayer, Long> nextDodgeAt = new WeakHashMap<>();

        numen.on(CompanionEvent.SPAWN, companions::add);
        numen.on(CompanionEvent.REMOVE, companions::remove);
        numen.on(CompanionEvent.HURT, hurt -> onHurt(hurt.companion(), hurt.source(), nextDodgeAt));

        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) {
                return;
            }
            companions.removeIf(her -> her.isRemoved() || !her.isAlive());
            for (NumenPlayer her : companions) {
                tickGuard(her);
            }
        });
    }

    /** 每刻一次:有威胁举挡,没威胁(或不在战斗模式)放挡。 */
    private static void tickGuard(NumenPlayer her) {
        ServerPlayerPatch patch = EpicFight.patchOf(her);
        if (patch == null) {
            return;
        }
        if (!patch.isEpicFightMode()) {
            if (guarding(her, patch)) {
                EpicFight.guard(her, false);
            }
            return;
        }
        boolean threatened = nearestThreatWithinReach(her) != null;
        if (threatened && !guarding(her, patch)) {
            EpicFight.guard(her, true);
        } else if (!threatened && guarding(her, patch)) {
            EpicFight.guard(her, false);
        }
    }

    /** 挨打那一刻:转向伤害来源,向后滚开。 */
    private static void onHurt(NumenPlayer her, DamageSource source, Map<NumenPlayer, Long> nextDodgeAt) {
        if (!inBattle(her)) {
            return;
        }
        long now = her.tickCount;
        Long next = nextDodgeAt.get(her);
        if (next != null && now < next) {
            return;
        }
        Entity from = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
        if (from == null) {
            return;
        }
        her.lookAt(EntityAnchorArgument.Anchor.EYES, from.getEyePosition());
        if (EpicFight.dodge(her, false).executed()) {
            nextDodgeAt.put(her, now + DODGE_COOLDOWN_TICKS);
        }
    }

    /** 附近有没有逼到能打到她的威胁。 */
    private static Mob nearestThreatWithinReach(NumenPlayer her) {
        Mob best = null;
        double bestDist = Double.MAX_VALUE;
        for (Mob mob : her.level().getEntitiesOfClass(Mob.class, her.getBoundingBox().inflate(SCAN_RADIUS),
                m -> m.isAlive())) {
            boolean hostile = mob instanceof Enemy;
            boolean targeting = mob.getTarget() == her;
            boolean aggrieved = mob.getLastHurtByMob() == her;
            if (!hostile && !targeting && !aggrieved) {
                continue;
            }
            double d = her.distanceTo(mob);
            if (d < bestDist) {
                best = mob;
                bestDist = d;
            }
        }
        return best != null && bestDist <= strikeRangeOf(best, her) ? best : null;
    }

    /** 怪物能打到她的中心距离(外接圆,与本体 {@code Menace.strikeRangeOf} 同一套几何)。 */
    private static double strikeRangeOf(Mob attacker, NumenPlayer victim) {
        double perAxis = attacker.getBbWidth() / 2.0 + MOB_ATTACK_REACH + victim.getBbWidth() / 2.0;
        return perAxis * Math.sqrt(2.0);
    }

    private static boolean guarding(NumenPlayer her, ServerPlayerPatch patch) {
        return EpicFight.guarding(patch, patch.getSkill(SkillSlots.GUARD));
    }

    private static boolean inBattle(NumenPlayer her) {
        ServerPlayerPatch patch = EpicFight.patchOf(her);
        return patch != null && patch.isEpicFightMode();
    }
}
