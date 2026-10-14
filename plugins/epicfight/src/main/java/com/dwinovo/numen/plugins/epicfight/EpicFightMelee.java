package com.dwinovo.numen.plugins.epicfight;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/**
 * 战斗模式下的近战挥击走史诗战斗的 {@code BASIC_ATTACK} 技能——有真正的挥砍动画,伤害走史诗战斗的伤害管线,
 * 连续调用就是连击。注册进 Numen 的近战登记处({@link MeleeStyles}),core 的 {@code AttackCompanionTask}
 * 在挥击那一刻问到这里,于是模型派的 {@code numen.fight.attack} 与自卫本能开的同一场仗都用这套打法。
 *
 * <p>和女仆(车万女仆)适配的差别:女仆是 NPC({@code HumanoidMobPatch}),用 {@code CombatBehaviors} 直接放动画;
 * 同伴是假玩家({@code ServerPlayerPatch}),没有 mob 的 AI 目标,只能经 {@code BASIC_ATTACK} 技能容器打——
 * 这是史诗战斗给玩家定义的那条正路,和真人按键走的是同一条服务器路径。
 */
final class EpicFightMelee implements MeleeStyle {

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
        EpicFight.Action done = EpicFight.attack(fighter);
        if (done.executed()) {
            if (!takeoverLogged) {
                takeoverLogged = true;
                Constants.LOG.info("[numen-epicfight] 战斗模式近战接管:挥击走 BASIC_ATTACK 技能(目标={})", target.getId());
            }
            return true;
        }
        // 没打出去:大多数时候是“上一段动作还没完”(连击节奏由史诗战斗自己卡),这不是错;
        // 只把真的失败(没体力/不在战斗模式/没技能)记下来,方便排查。
        if (Constants.LOG.isDebugEnabled()) {
            Constants.LOG.debug("[numen-epicfight] 挥击未执行: {}", done.reason());
        }
        return false;
    }
}
