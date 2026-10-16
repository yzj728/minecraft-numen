package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.ability.SuperSlashArts;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方,别处(API、技能、入口)
 * 都不碰它的类。
 *
 * <h2>核心:直接释放招式,不搓连段</h2>
 * 拔刀剑每一招就是一个 {@link ComboState} 状态机:进场调 {@code clickAction}(第一击)、持续期每 tick 调
 * {@code tickAction}(多段刀光/位移/收刀/眩晕)、到点按 {@code nextOfTimeout} 自动衔接下一段。所谓「放一招」就是
 * {@code updateComboSeq(entity, 那一招的 combo key)} 切过去——进场即放第一击,后续由 {@link SlashBladeTicker}
 * 逐 tick 播完。所以「直接释放」= 直接指定招式名 → 切到对应 combo,<b>完全绕开玩家的蓄力判定和 {@code doArts}
 * 的档位抽象</b>。这里 {@link #release} 就干这一件事。
 *
 * <h2>放 SA 的正确链路(照车万女仆「真正的力量」{@code SlashBladeAttackUtils.doSlashArts})</h2>
 * 放剑技有一步<b>必须</b>:post {@link SlashBladeEvent.PerformSlashArtEvent}(拔刀剑释放剑技的事件钩子,别的模组
 * 和它自己都靠它)。跳过它,最后的收尾段就出不来。{@link #doArt} 走这一步;{@link #release} 走「直接切 combo」的路(用于非 SA 招式)。
 */
public final class SlashBlade {

    private SlashBlade() {}

    /** 一次剑技(SA)的返回。 */
    public record Art(boolean executed, String sa, String reason) {}

    /** 一次挥刀、放剑技或一套连招的动作返回。 */
    public record Action(boolean executed, String reason) {}

    /** 一件物品是不是拔刀剑。 */
    public static boolean isSlashBlade(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemSlashBlade;
    }

    /**
     * 挥一下拔刀剑——照女仆 {@code SlashBladeCompat.swingSlashBlade} 的口径,普通挥击。
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

    /** 这把刀断了没有。不是拔刀剑返回 false。 */
    public static boolean isBroken(ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(ISlashBladeState::isBroken).orElse(false);
    }

    /** 这把刀附加的剑技(SA)的注册 key;没有则拔刀剑回退判断斩。 */
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

    /** 这把刀附的特殊效果(SE)的注册 key 列表;不是拔刀剑返回空表。SE 是被动效果,不是主动放的招。 */
    public static List<String> specialEffects(ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return List.of();
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> {
                    List<String> out = new ArrayList<>();
                    for (ResourceLocation se : state.getSpecialEffects()) {
                        if (se != null) {
                            out.add(se.toString());
                        }
                    }
                    return out;
                })
                .orElseGet(List::of);
    }

    /** 能不能放超SA:妖刀 + 锐利边缘 + 非断刀 + 非封印 + 满耐久 + 站地面。 */
    private static boolean canSuper(ItemStack blade, LivingEntity entity) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        EnumSet<SwordType> types = SwordType.from(blade);
        boolean basic = types.contains(SwordType.BEWITCHED) && types.contains(SwordType.FIERCEREDGE)
                && !types.contains(SwordType.SEALED);
        if (!basic || !entity.onGround()) {
            return false;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> !state.isBroken() && state.getDamage() == 0)
                .orElse(false);
    }

    /** 放一次超SA——照官方 {@link SuperSlashArts#releaseSSA},满耐久才放、放时耗一半耐久。 */
    private static Art superArtImpl(LivingEntity entity, ItemStack blade) {
        if (!(entity instanceof ServerPlayer player)) {
            return new Art(false, artKey(blade), "not a player entity");
        }
        if (!canSuper(blade, entity)) {
            return new Art(false, artKey(blade),
                    "the blade needs to be bewitched (enchanted + named), fierce-edged (1000+ kills), "
                            + "unbroken, unsealed, at full durability, and on the ground");
        }
        SuperSlashArts.releaseSSA(player);
        return new Art(true, artKey(blade), "");
    }

    /**
     * 放一次剑技(SA)。{@code type} 是 {@code normal} / {@code just} / {@code super} / {@code random} 之一。
     * 不带目标:只放,不替你面向(调用方负责先转身)。
     */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        return art(entity, blade, type, null);
    }

    /**
     * 放一次剑技,带目标——放之前 {@code lookAt(FEET, target)} 面向它。{@code target} 为 null 时不面向。
     */
    public static Art art(LivingEntity entity, ItemStack blade, String type, Entity target) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        if (target != null) {
            entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        }
        String t = type == null ? "random" : type.toLowerCase(Locale.ROOT);
        if ("random".equals(t)) {
            if (canSuper(blade, entity) && entity.getRandom().nextBoolean()) {
                return superArtImpl(entity, blade);
            }
            SlashArts.ArtsType half = entity.getRandom().nextBoolean()
                    ? SlashArts.ArtsType.Success : SlashArts.ArtsType.Jackpot;
            return blade.getCapability(ItemSlashBlade.BLADESTATE)
                    .map(state -> doArt(entity, state, half))
                    .orElse(new Art(false, "", "the slash blade has no blade state"));
        }
        if ("super".equals(t)) {
            return superArtImpl(entity, blade);
        }
        SlashArts.ArtsType artsType = switch (t) {
            case "just" -> SlashArts.ArtsType.Jackpot;
            default -> SlashArts.ArtsType.Success;
        };
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            return doArt(entity, state, artsType);
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    /** 放一次普通/完美剑技——照车万女仆 doSlashArts:post PerformSlashArtEvent,取改写的 combo 再切。 */
    private static Art doArt(LivingEntity entity, ISlashBladeState state, SlashArts.ArtsType artsType) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        if (!SwordType.from(entity.getMainHandItem()).contains(SwordType.ENCHANTED)) {
            return new Art(false, artOf(state), "this blade is not enchanted; it has no slash art yet");
        }
        int elapsed = artsType == SlashArts.ArtsType.Jackpot ? 10 : 20;
        ResourceLocation comboLoc = state.getSlashArts().doArts(artsType, entity);
        SlashBladeEvent.PerformSlashArtEvent event =
                new SlashBladeEvent.PerformSlashArtEvent(entity, elapsed, state, comboLoc, artsType);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return new Art(false, artOf(state), "the slash art was cancelled");
        }
        comboLoc = event.getComboState();
        ComboState combo = ComboStateRegistry.REGISTRY.get().getValue(comboLoc);
        if (combo == null || Objects.equals(comboLoc, ComboStateRegistry.NONE.getId())) {
            return new Art(false, artOf(state), "this blade's slash art is not supported");
        }
        state.updateComboSeq(entity, comboLoc);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }

    /** 放一次超SA的别名——等价于 {@code art(..., "super")}。 */
    public static Action superArt(LivingEntity entity, ItemStack blade) {
        SlashBlade.Art done = art(entity, blade, "super");
        return new Action(done.executed(), done.reason());
    }

    /** 招式名 → combo key 的映射(1.1.23 ComboStateRegistry 里确认的键)。 */
    private static final Map<String, ResourceLocation> MOVES = Map.of(
            "rapid_slash", ComboStateRegistry.RAPID_SLASH.getId(),      // 疾走居合:前冲+连续刀光
            "upperslash", ComboStateRegistry.UPPERSLASH.getId(),        // 挑空
            "upperslash_jump", ComboStateRegistry.UPPERSLASH_JUMP.getId(), // 跃升斩:升空+下劈
            "aerial_cleave", ComboStateRegistry.AERIAL_CLEAVE.getId(),  // 空中下劈
            "rising_star", ComboStateRegistry.RISING_STAR.getId()       // 晨星:上挑连击
    );

    /**
     * <b>直接释放一个招式,完全跳过搓招/蓄力</b>。{@code move} 是招式名:
     * {@code rapid_slash}(疾走居合)、{@code upperslash}(挑空)、{@code upperslash_jump}(跃升斩)、
     * {@code aerial_cleave}(空中下劈)、{@code rising_star}(晨星)。直接 {@code updateComboSeq} 切到对应 combo,
     * 进场即放第一击,后续多段由 {@link SlashBladeTicker} 逐 tick 播完。
     */
    public static Action release(LivingEntity entity, ItemStack blade, String move) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        ResourceLocation combo = MOVES.get(move == null ? "" : move.toLowerCase(Locale.ROOT));
        if (combo == null) {
            return new Action(false, "unknown move: " + move
                    + " (known: rapid_slash, upperslash, upperslash_jump, aerial_cleave, rising_star)");
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            state.updateComboSeq(entity, combo);
            entity.swing(InteractionHand.MAIN_HAND);
            return new Action(true, "");
        }).orElse(new Action(false, "the slash blade has no blade state"));
    }

    /**
     * 放一整套连击(预制),由服务端随机选起手:地面随机进「普攻连段 A」或「疾走居合」,空中进「空中连段」。
     */
    public static Action combo(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        RandomSource random = entity.getRandom();
        ResourceLocation opener = entity.onGround()
                ? (random.nextBoolean() ? ComboStateRegistry.COMBO_A1.getId() : ComboStateRegistry.RAPID_SLASH.getId())
                : ComboStateRegistry.AERIAL_CLEAVE.getId();
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            state.updateComboSeq(entity, opener);
            entity.swing(InteractionHand.MAIN_HAND);
            return new Action(true, "");
        }).orElse(new Action(false, "the slash blade has no blade state"));
    }

    /**
     * 逐 tick 推进刀当前的 combo 时间线——复现 {@code ItemSlashBlade.inventoryTick} 里
     * {@code cs.tickAction(living)} 那一段。假玩家没有被动 tick 链,由 {@link SlashBladeTicker} 每服务端刻调用这里。
     */
    public static void tickAction(LivingEntity entity) {
        ItemStack blade = entity.getMainHandItem();
        if (!isSlashBlade(blade)) {
            return;
        }
        blade.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
            ResourceLocation loc = state.resolvCurrentComboState(entity);
            ComboState cs = ComboStateRegistry.REGISTRY.get().getValue(loc);
            if (cs == null) {
                cs = ComboStateRegistry.NONE.get();
            }
            if (cs != null) {
                cs.tickAction(entity);
            } else if (!loc.equals(state.getComboRoot())) {
                state.setComboSeq(state.getComboRoot());
            }
        });
    }

    /** 这把刀实际的剑技 key(完整注册名)。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }
}
