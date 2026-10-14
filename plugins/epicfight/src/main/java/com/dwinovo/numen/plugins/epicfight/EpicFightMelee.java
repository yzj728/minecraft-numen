package com.dwinovo.numen.plugins.epicfight;

import com.dwinovo.numen.api.combat.MeleeStyle;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

/**
 * 战斗模式下的近战挥击走史诗战斗的 {@code BASIC_ATTACK} 技能——有真正的挥砍动画,伤害走史诗战斗的伤害管线,
 * 连续调用就是连击。注册进 Numen 的近战登记处({@link MeleeStyles}),core 的 {@code AttackCompanionTask}
 * 在挥击那一刻问到这里,于是模型派的 {@code numen.fight.attack} 与自卫本能开的同一场仗都用这套打法。
 */
final class EpicFightMelee implements MeleeStyle {

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
        // 上一段攻击动画没走完就再等一拍:节奏由史诗战斗自己卡,这里只是别每刻都转头、别瞎喊
        if (!patch.getEntityState().canBasicAttack()) {
            return false;
        }
        InputDriver.lookAt(fighter, target.getEyePosition());
        fighter.setSprinting(false);
        return EpicFight.attack(fighter).executed();
    }
}
