package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * 一种「接管近战挥击」的战斗系统——近战动作模组(史诗战斗这类)把它注册进来(见 {@link MeleeStyles#register}),
 * Numen 自己的战斗系统在要挥这一下的时候问到这里,而不是自己 {@code player.attack()}:模组自己放攻击动画、
 * 算伤害、卡节奏,和真人按键走同一条路。
 *
 * <h2>为什么走这个口子,而不是插件自己派任务</h2>
 * 和枪械({@link RangedFirearm})同一个理由:同伴的身体是三层抢的,插件自己派的任务抢不过自卫本能。把它注册进
 * 战斗系统之后,模型派的 {@code numen.fight.attack}、自卫本能开的同一场仗,挥击那一刻都会问到这里——近战动作
 * 成了身体认识的一种打法,不是插件在旁边抢身体的另一个东西。
 *
 * <h2>实现的规矩</h2>
 * <ul>
 *   <li>实现在服务端主线程被调,每服务端刻一次(只在这一刻真要挥的时候才被问);它自己记节奏,
 *       不占用任何任务槽。</li>
 *   <li>{@link #applies} 与 {@link #strike} 都必须能对任何身体/目标安全回答,不抛异常。</li>
 *   <li>攻击方向由实现自己转(开打前把身体转向目标),别每刻都转——那会带偏移动。</li>
 * </ul>
 */
public interface MeleeStyle {

    /** 这一刻的这次近战挥击该不该由它接管。 */
    boolean applies(NumenPlayer fighter, LivingEntity target);

    /**
     * 打一下。
     *
     * @return true = 这一下真的执行了;false = 没执行(还在上一段动作里、没体力、状态不允许……),
     *         战斗下一拍再问。
     */
    boolean strike(NumenPlayer fighter, LivingEntity target);
}
