package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方,别处(API、技能、入口)
 * 都不碰它的类。
 *
 * <h2>为什么可以直接引用它的类</h2>
 * 拔刀剑开源且类名不混淆,所以直接编译依赖它,拿真正的 {@code ItemSlashBlade} / {@code AttackManager} /
 * {@code CapabilitySlashBlade} 驱动同伴。运行时那份由玩家自己装上的模组提供({@code compileOnly}),
 * 本插件不携带它一个字节。
 *
 * <h2>打法的出处:车万女仆的 {@code SlashBladeCompat}</h2>
 * 女仆适配拔刀剑只做两件事——认刀({@code stack.getItem() instanceof ItemSlashBlade})、挥刀
 * ({@code AttackManager.doSlash(entity, roll, Vec3.ZERO, false, false, 1.0, KnockBacks.smash)})。
 * {@code AttackManager.doSlash} 是拔刀剑的服务器端公开口,对任意 {@link LivingEntity} 都可用
 * (不要求是 NPC),伤害由拔刀剑自己的攻击管线结算。同伴是假玩家,直接用这条口子,不经过
 * 任何技能容器或补丁——这正是女仆(NPC)与同伴(玩家)都能走的同一条路,与史诗战斗那套
 * {@code PlayerPatch} 完全无关。
 */
public final class SlashBlade {

    private SlashBlade() {}

    /** 一件物品是不是拔刀剑。 */
    public static boolean isSlashBlade(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemSlashBlade;
    }

    /**
     * 挥一下拔刀剑——照女仆 {@code SlashBladeCompat.swingSlashBlade} 的口径。
     *
     * <p>{@code roll} 是这一刀的朝向偏移(度),女仆取 {@code random.nextInt(60) - 30}(±30°);
     * 同伴要打中目标,由调用方先把身体转向目标再挥,这里滚角给 0,让刀刃沿身体朝向打出。
     * {@code KnockBacks.smash} 与女仆一致:击退按拔刀剑的 smash 档结算。
     *
     * @return 这一刀是否真的挥了出去(拔刀剑状态允许时);拔刀剑不在手上时为 false
     */
    public static boolean swing(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        AttackManager.doSlash(entity, 0, Vec3.ZERO, false, false, 1.0, KnockBacks.smash);
        blade.getCapability(CapabilitySlashBlade.BLADESTATE)
                .ifPresent(state -> state.setLastActionTime(entity.level().getGameTime()));
        return true;
    }

    /** 这把刀断了没有(拔刀剑自己的耐久/断刀状态)。不是拔刀剑返回 false。 */
    public static boolean isBroken(ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        return blade.getCapability(CapabilitySlashBlade.BLADESTATE)
                .map(state -> state.isBroken()).orElse(false);
    }
}
