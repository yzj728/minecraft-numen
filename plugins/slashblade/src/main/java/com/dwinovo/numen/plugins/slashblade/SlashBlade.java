package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.CapabilitySlashBlade;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;

import java.util.Locale;
import java.util.Objects;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方,别处(API、技能、入口)
 * 都不碰它的类。
 *
 * <h2>为什么可以直接引用它的类</h2>
 * 拔刀剑开源且类名不混淆,所以直接编译依赖它,拿真正的 {@code ItemSlashBlade} / {@code AttackManager} /
 * {@code CapabilitySlashBlade} / {@code SlashArts} 驱动同伴。运行时那份由玩家自己装上的模组提供
 * ({@code compileOnly}),本插件不携带它一个字节。
 *
 * <h2>普通挥刀的出处:车万女仆的 {@code SlashBladeCompat}</h2>
 * 女仆适配拔刀剑只做两件事——认刀({@code stack.getItem() instanceof ItemSlashBlade})、挥刀
 * ({@code AttackManager.doSlash(entity, roll, Vec3.ZERO, false, false, 1.0, KnockBacks.smash)})。
 * {@code AttackManager.doSlash} 是拔刀剑的服务器端公开口,对任意 {@link LivingEntity} 都可用
 * (不要求是 NPC),伤害由拔刀剑自己的攻击管线结算。同伴是假玩家,直接用这条口子,不经过
 * 任何技能容器或补丁。
 *
 * <h2>剑技(SA)的出处:车万女仆「真正的力量」那类附属</h2>
 * 剑技不是普通挥刀,是拔刀剑附加的 SA({@link SlashArts})。让女仆放剑技的模组
 * (true-power-of-maid / native-power-of-maid)与 mrqx 的 Slashblade Core 走的是同一条公开口子:
 * <pre>{@code
 * ResourceLocation combo = state.getSlashArts().doArts(type, user);   // 读这把刀实际的 SA 再执行
 * SlashBladeEvent.PerformSlashArtEvent ev = new SlashBladeEvent.PerformSlashArtEvent(user, elapsed, state, combo, type);
 * MinecraftForge.EVENT_BUS.post(ev);                                  // 其他模组可以拦/改
 * if (!ev.isCanceled() && 非 NONE) state.updateComboSeq(user, combo);  // 没被取消才落地挥出
 * }</pre>
 * {@code state.getSlashArts()} 读的是这把刀自己的 SA key({@link ISlashBladeState#getSlashArtsKey()}),所以
 * <b>天然适配不同的拔刀剑</b>——刀上附什么 SA,这里就放什么 SA,不用为每一种刀各写一份。这是假玩家(同伴)
 * 与女仆(NPC)都能走的同一条服务器端口,与真人长按右键那条路径共用 {@code getSlashArts().doArts} 与
 * {@code updateComboSeq} 两件事,伤害与资源消耗(骄傲之魂等)由拔刀剑自己的连招状态机结算。
 */
public final class SlashBlade {

    private SlashBlade() {}

    /** 一次剑技的返回。 */
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
                .map(ISlashBladeState::isBroken).orElse(false);
    }

    /** 这把刀附加的剑技(SA)的注册 key;拔刀剑一定会有一个(没有的话拔刀剑自己回退到判断斩)。 */
    public static String artKey(ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return "";
        }
        return blade.getCapability(CapabilitySlashBlade.BLADESTATE)
                .map(state -> {
                    ResourceLocation key = state.getSlashArtsKey();
                    // getSlashArtsKey 可能为 null(还没写进 NBT),但 getSlashArts() 永不返回 null(回退判断斩);
                    // 这里兜底也回退到判断斩,与 ISlashBladeState.getSlashArts 的行为一致。
                    return key == null ? "slashblade:judgement_cut" : key.toString();
                })
                .orElse("");
    }

    /** 放一次剑技(SA)。{@code type} 是 {@code normal}/{@code just}/{@code super} 的哪一种。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        return blade.getCapability(CapabilitySlashBlade.BLADESTATE).map(state -> {
            return doArt(entity, state, typeOf(type));
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    private static SlashArts.ArtsType typeOf(String type) {
        String t = type == null ? "normal" : type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "just" -> SlashArts.ArtsType.Jackpot;
            case "super" -> SlashArts.ArtsType.Super;
            default -> SlashArts.ArtsType.Success;
        };
    }

    /** 每一档剑技假装的蓄力 tick 数,与女仆附属一致:普通 20,Just 10,Super 20。 */
    private static int elapsedOf(SlashArts.ArtsType type) {
        return type == SlashArts.ArtsType.Jackpot ? 10 : 20;
    }

    /**
     * 放一次剑技——照车万女仆「真正的力量」与 mrqx Slashblade Core 的 {@code doSlashArts} 口径。
     *
     * <p>先读这把刀自己的 SA({@code getSlashArts()},按 {@code getSlashArtsKey} 解析、没写则回退判断斩),
     * 再经 {@code PerformSlashArtEvent} 让别的模组(装饰、数值、true-power 之类)有机会拦或改;没被取消、结果也不是
     * NONE,才 {@code updateComboSeq} 真正落地。伤害、断刀、骄傲之魂消耗都由拔刀剑的连招状态机({@code clickAction})
     * 结算,不在本插件手里。
     */
    private static Art doArt(LivingEntity entity, ISlashBladeState state, SlashArts.ArtsType type) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        SlashArts sa = state.getSlashArts();
        ResourceLocation combo = sa.doArts(type, entity);
        int elapsed = elapsedOf(type);
        SlashBladeEvent.PerformSlashArtEvent event =
                new SlashBladeEvent.PerformSlashArtEvent(entity, elapsed, state, combo, type);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return new Art(false, artOf(state), "the slash art was canceled by another mod");
        }
        combo = event.getComboState();
        ComboState cs = ComboStateRegistry.REGISTRY.get().getValue(combo);
        if (cs == null || Objects.equals(combo, ComboStateRegistry.NONE.getId())) {
            return new Art(false, artOf(state), "no slash art effect for this art type");
        }
        state.updateComboSeq(entity, combo);
        return new Art(true, artOf(state), "");
    }

    /** 这把刀实际的剑技 key(与 {@link #artKey} 同源,只是手上已经有 {@code state},少查一遍 capability)。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }
}
