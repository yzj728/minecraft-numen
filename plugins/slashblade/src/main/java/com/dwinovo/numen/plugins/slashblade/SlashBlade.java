package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方,别处(API、技能、入口)
 * 都不碰它的类。
 *
 * <h2>为什么可以直接引用它的类</h2>
 * 拔刀剑开源且类名不混淆,所以直接编译依赖它,拿真正的 {@code ItemSlashBlade} / {@code AttackManager} /
 * {@code ISlashBladeState} 驱动同伴。运行时那份由玩家自己装上的模组提供({@code compileOnly}),
 * 本插件不携带它一个字节。
 *
 * <h2>版本基准:1.1.23(1.20.1 线)</h2>
 * {@code gradle.properties} 的 {@code slashblade_version=1.1.23-1.20.1} 对着整合包实际在用的那一版编。
 * 这一代(1.1.x)的剑技释放入口是 {@link ISlashBladeState#doChargeAction}:真人玩家松右键走的就是它
 * (见 {@code ItemSlashBlade.releaseUsing}),内部完整做了"蓄力判定 → {@code getSlashArts().doArts} → 优先级 →
 * {@code updateComboSeq}"这条链,<b>天然适配不同的拔刀剑</b>——刀上附什么 SA({@code getSlashArtsKey()})就放什么 SA。
 * 与 1.9.x 那套 {@code PerformSlashArtEvent} 不是一回事,别拿它来迁。
 *
 * <h2>普通挥刀的出处:车万女仆的 {@code SlashBladeCompat}</h2>
 * 女仆适配拔刀剑只做两件事——认刀({@code stack.getItem() instanceof ItemSlashBlade})、挥刀
 * ({@code AttackManager.doSlash(...)}),是拔刀剑的服务器端公开口,对任意 {@link LivingEntity} 都可用,伤害由
 * 拔刀剑自己的攻击管线结算。同伴是假玩家,直接用这条口子,不经过任何技能容器或补丁。
 */
public final class SlashBlade {

    private SlashBlade() {}

    /** 一次剑技(SA)的返回。 */
    public record Art(boolean executed, String sa, String reason) {}

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
        blade.getCapability(ItemSlashBlade.BLADESTATE)
                .ifPresent(state -> state.setLastActionTime(entity.level().getGameTime()));
        return true;
    }

    /** 这把刀断了没有(拔刀剑自己的耐久/断刀状态)。不是拔刀剑返回 false。 */
    public static boolean isBroken(ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(ISlashBladeState::isBroken).orElse(false);
    }

    /** 这把刀附加的剑技(SA)的注册 key;拔刀剑一定会有一个(没有的话拔刀剑自己回退到判断斩)。 */
    public static String artKey(ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return "";
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> {
                    ResourceLocation key = state.getSlashArtsKey();
                    return key == null ? "slashblade:judgement_cut" : key.toString();
                })
                .orElse("");
    }

    /** 放一次剑技(SA)。{@code type} 是 {@code normal} / {@code just} 之一(1.1.23 没有 super 档)。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            return doArt(entity, state, type);
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    /**
     * 放一次剑技——照真人松右键的口径,直接走 {@link ISlashBladeState#doChargeAction}。
     *
     * <p>{@code doChargeAction} 内部按 {@code elapsed} 判定这一下是普通(Success)还是居合时机(Jackpot):
     * {@code SlashArts.ChargeTicks=9},默认 just 判定窗为 {@code [9,9+3)},所以
     * {@code elapsed=20} 落在 Success、{@code elapsed=10} 落在 Jackpot。这与车万女仆「真正的力量」那类附属
     * 用 20/10 同一个数。它还要检查当前连段是否允许(不可打断就返回 NONE),断了/封印了也返回 NONE——
     * "这一下该不该放得出来"由拔刀剑自己说了算,本插件不越俎代庖。
     */
    private static Art doArt(LivingEntity entity, ISlashBladeState state, String type) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        int elapsed = "just".equals(normalize(type)) ? 10 : 20;
        ResourceLocation combo = state.doChargeAction(entity, elapsed);
        if (combo == null || combo.equals(ComboStateRegistry.NONE.getId())) {
            return new Art(false, artOf(state), "no slash art effect right now (not ready or mid-combo)");
        }
        return new Art(true, artOf(state), "");
    }

    private static String normalize(String type) {
        return type == null ? "normal" : type.toLowerCase(Locale.ROOT);
    }

    /** 这把刀实际的剑技 key(与 {@link #artKey} 同源,手上已有 {@code state},少查一遍 capability)。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }
}
