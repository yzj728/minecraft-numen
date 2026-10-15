package com.dwinovo.numen.plugins.epicfight;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/**
 * 战斗模式下的近战挥击<b>直接放史诗战斗的攻击动画</b>(照女仆 NPC 的 {@code AnimatedAttackGoal} 逻辑),
 * 不经 BASIC_ATTACK 技能容器——同伴不是真人、不搓招。注册进 Numen 的近战登记处({@link MeleeStyles}),
 * core 的 {@code AttackCompanionTask} 在挥击那一刻问到这里,于是模型派的 {@code numen.fight.attack} 与
 * 自卫本能开的同一场仗都用这套打法。伤害由史诗战斗的 {@code AttackAnimation} 攻击帧自动结算。
 *
 * <p>只在 battle 模式接管;vanilla 模式 {@link #applies} 返回 false,原版挥击照旧,枪械(远程路径)也不受影响。
 */
final class EpicFightMelee implements MeleeStyle {

    private static final Logger LOG = LoggerFactory.getLogger("numen-epicfight");

    /** 连击序号:每次挥击 +1,由 {@link EpicFight#swing} 对武器连击表取模。单例跨同伴共享,
     *  只影响连击动画的序号连续性,不影响正确性(伤害与动画照常)。 */
    private int comboStep;

    /** 只记一次“接管了挥击”:证明这条路径真的走到了,不是每次挥击都有一行。 */
    private boolean takeoverLogged;

    @Override
    public boolean applies(NumenPlayer fighter, LivingEntity target) {
        ServerPlayerPatch patch = EpicFight.patchOf(fighter);
        if (patch == null || !patch.isEpicFightMode()) {
            return false;
        }
        // 弓、弩这类远程物品不走基础挥击;让原版战斗系统继续管它们(战斗系统自己会去拉弓/开枪)
        EpicFight.Weapon held = EpicFight.weaponOf(fighter.getMainHandItem(), patch);
        return !"BOW".equals(held.category()) && !"CROSSBOW".equals(held.category());
    }

    @Override
    public boolean strike(NumenPlayer fighter, LivingEntity target) {
        ServerPlayerPatch patch = EpicFight.patchOf(fighter);
        if (patch == null || !patch.isEpicFightMode()) {
            return false;
        }
        InputDriver.lookAt(fighter, target.getEyePosition());
        fighter.setSprinting(false);
        EpicFight.Action done = EpicFight.swing(fighter, comboStep);
        if (done.executed()) {
            comboStep++;
            if (!takeoverLogged) {
                takeoverLogged = true;
                LOG.info("战斗模式近战接管:直接放攻击动画(目标={})", target.getId());
            }
            return true;
        }
        // 没放出去:大多数时候是“上一段动画还没完”(连击节奏由史诗战斗自己卡),这不是错;
        // 只把真的失败(没体力/不在战斗模式/没动画)记下来,方便排查。
        if (LOG.isDebugEnabled()) {
            LOG.debug("攻击动画未播放: {}", done.reason());
        }
        return false;
    }
}
