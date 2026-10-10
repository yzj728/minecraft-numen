package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 一种「会响的远程武器」——枪械模组的插件把它注册进来(见 {@link Firearms#register}),Numen 自己的战斗系统
 * 就认得这种武器了:挑装备时它算远程({@code Loadout} 问 {@link #isFirearm})、开打时每服务端刻问一次
 * {@link #tickShoot} 开一枪。
 *
 * <h2>为什么走这个口子,而不是插件自己派任务</h2>
 * 同伴的身体是三层抢的:反射(自卫/逃跑)最高、同步短活其次、任务槽最低。插件自己派的开火任务抢不过反射——
 * 怪一逼近,自卫链就接管身体按原版剑/弓逻辑打,枪永远轮不到。把枪注册进战斗系统之后,自卫链开的那场仗、模型派的
 * {@code numen.fight.attack},用的都是同一套走位/朝向/站定,而且它们自己就会问到这里——枪成了身体认识的一种武器,
 * 不是插件在旁边抢身体的另一个东西。
 *
 * <h2>实现的规矩</h2>
 * <ul>
 *   <li>实现在服务端主线程被调,每服务端刻一次,直到攻击收场;它自己记枪的状态机(拉栓/换弹/开镜/冷却),
 *       不占用任何任务槽。</li>
 *   <li>{@link #isFirearm} 与 {@link #tickShoot} 都必须能对任何物品安全回答——战斗系统扫的是整个背包。</li>
 *   <li>开火那一刻才把身体转向目标:每刻转会带偏移动(和原版弓"松手前才瞄"同一个道理)。</li>
 * </ul>
 */
public interface RangedFirearm {

    /** 这件物品是不是它认得的枪。 */
    boolean isFirearm(ItemStack stack);

    /**
     * 对着目标打一发。实现自己把枪摆对(拉栓、换弹、开镜、等冷却都归它)。
     *
     * @param shooter 开枪的同伴
     * @param gun     它背包里挑出来的那件枪械物品
     * @param target  要打的实体,这一拍还活着
     * @return {@code "fired"} = 这一拍打出去了一发;{@code null} = 这一拍没打(冷却/换弹/拉栓中),下一拍再问;
     *         其他任意非空字符串 = 开不了枪的原因(没子弹、没枪……),战斗按它记哑火,连续几次就判这把枪不顶用。
     */
    String tickShoot(NumenPlayer shooter, ItemStack gun, LivingEntity target);
}
