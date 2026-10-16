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
import java.util.Objects;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方,别处(API、技能、入口)
 * 都不碰它的类。
 *
 * <h2>版本基准:1.1.23(1.20.1 线,整合包实际在用)</h2>
 * 这一代每一招都是一个 {@link ComboState} 状态机链:进场调 {@code clickAction}、持续期间每 tick 调
 * {@code tickAction}(按 {@code TimeLineTickAction} 的时间线放出多段刀光 / 位移 / 收刀 / 命中眩晕),
 * 到点按 {@code nextOfTimeout} 自动衔接下一段。真玩家靠 {@link ItemSlashBlade#inventoryTick} 里的
 * {@code cs.tickAction(living)} 逐 tick 驱动;假玩家没有这条被动链,由 {@link SlashBladeTicker} 在服务端
 * 每 tick 复现同一条链(见该类)。
 *
 * <h2>放剑技的正确链路(照车万女仆「真正的力量」的 {@code SlashBladeAttackUtils.doSlashArts})</h2>
 * 放一次剑技不是「doArts 拿 combo、updateComboSeq 切过去」三行就完事——中间有一环<b>必须</b>:
 * <ol>
 *   <li>{@code lookAt(FEET, target)} 面向目标(它朝面朝方向放,不面向等于空放);</li>
 *   <li>{@code getSlashArts().doArts(type, user)} 拿目标 combo;</li>
 *   <li><b>post {@link SlashBladeEvent.PerformSlashArtEvent}</b>——这是拔刀剑释放剑技的事件钩子,别的模组
 *       和拔刀剑自己都靠它。跳过它,最后的收尾段就出不来;</li>
 *   <li>取 {@code event.getComboState()}(事件可能改写),判非取消、非 NONE,再 {@code updateComboSeq}。</li>
 * </ol>
 * 上一版漏了第 3 步,导致「起手能放、最后的收尾段放不出」。
 *
 * <h2>预制招式:绕开蓄力,按「档」取招</h2>
 * 真人放 SA 要长按右键、按蓄力时长判「普通/完美/失败」,完美窗口只有 3~4 tick。但程序不经过那条判定:
 * {@link SlashArts#doArts} 按 {@code ArtsType} 直接返回目标 combo,{@code elapsed} 只是喂给事件的一个口径
 * (完美=10、普通=20,照车万女仆),不影响成败。超SA 走 {@link SuperSlashArts#releaseSSA}(额外耗一半耐久)。
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
     *
     * <p><b>完全预制、绕开蓄力</b>:普通/完美直接经 {@link SlashArts#doArts} 按档位拿 combo、post
     * {@link SlashBladeEvent.PerformSlashArtEvent}、切过去,多段由 {@link SlashBladeTicker} 逐 tick 播完;
     * 超SA 走官方 {@link SuperSlashArts#releaseSSA}。{@code random} 时按刀状态随机挑档。
     */
    public static Art art(LivingEntity entity, ItemStack blade, String type, Entity target) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        if (target != null) {
            entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        }
        String t = type == null ? "random" : type.toLowerCase(Locale.ROOT);
        // random 要在「放超SA」上走 releaseSSA,不能把 Super 混进 doArts 的普通/完美里(那会绕开耐久消耗)
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

    /**
     * 放一次普通/完美剑技——照车万女仆 {@code SlashBladeAttackUtils.doSlashArts}:post
     * {@link SlashBladeEvent.PerformSlashArtEvent},取事件改写的 combo 再切。超SA 不走这里(要耗一半耐久)。
     */
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

    /**
     * 放一整套连击(预制),由服务端随机选起手:地面随机进「普攻连段 A」或「疾走居合」,空中进「空中连段」。
     * 起手后整条连段树由 {@link SlashBladeTicker} 逐 tick 自动推进、自动衔接、自动收刀——模型只喊
     * {@code slashblade.blade.combo()},不用想套路。
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
     * {@code cs.tickAction(living)} 那一段,让多段刀光 / 位移 / 收刀 / 命中判定按拔刀剑自己的时间线放出来。
     * 假玩家没有被动 tick 链,由 {@link SlashBladeTicker} 每服务端刻调用这里。
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
