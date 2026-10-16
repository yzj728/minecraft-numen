package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.ability.SuperSlashArts;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

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
 * <h2>为什么不再用 helper 简版刀光</h2>
 * 上一版错误地只调 {@code ISlashBladeState#doChargeAction}(推进状态)加各 SA 的 helper 静态方法
 * ({@code JudgementCut.doJudgementCutSuper} 等)补特效。但那些 helper 只是"放一片一次性刀光"的简版,
 * <b>不是</b> {@code ComboState.tickAction} 那套多段时间线——判断斩真正的"收刀冲刺 + 连续刀光 + 收刀音效
 * + 命中眩晕"整段都放不出来,只剩一道刀光。正确做法是让刀切到目标 combo,再由 ticker 逐 tick 驱动它自己
 * 的时间线,忠实复现真人。
 *
 * <h2>程序化搓招(不让大模型想怎么搓)</h2>
 * 模型只喊一个高层指令({@code slashblade.blade.art()}),服务端读当前刀的 SA、经 {@link SlashArts#doArts}
 * 得到目标 combo 并切过去,后续全套由 ticker 驱动。天然适配不同的刀:附什么 SA 就放什么招。
 */
public final class SlashBlade {

    private SlashBlade() {}

    /** 一次剑技(SA)的返回。 */
    public record Art(boolean executed, String sa, String reason) {}

    /** 一次挥刀或放剑技的动作返回。 */
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

    /**
     * 放一次剑技(SA)。{@code type} 是 {@code normal} / {@code just} 之一。
     *
     * <p>走拔刀剑自己的 {@link SlashArts#doArts} 得到目标 combo,再 {@link ISlashBladeState#updateComboSeq}
     * 切过去。切换即触发该段的 {@code clickAction}(进场动作),之后的多段刀光 / 位移 / 收刀由
     * {@link SlashBladeTicker} 逐 tick 驱动 {@code tickAction},忠实现真人。
     */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            return doArt(entity, state, type);
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    /** 放一次剑技——先判断刀/封印,再按 {@code normal}/{@code just} 放对应 combo。 */
    private static Art doArt(LivingEntity entity, ISlashBladeState state, String type) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        if (!SwordType.from(entity.getMainHandItem()).contains(SwordType.ENCHANTED)) {
            return new Art(false, artOf(state), "this blade is not enchanted; it has no slash art yet");
        }
        boolean just = "just".equals(normalize(type));
        SlashArts.ArtsType artsType = just ? SlashArts.ArtsType.Jackpot : SlashArts.ArtsType.Success;
        ResourceLocation combo = state.getSlashArts().doArts(artsType, entity);
        if (combo == null || combo.equals(ComboStateRegistry.NONE.getId())) {
            return new Art(false, artOf(state), "this blade's slash art is not supported");
        }
        state.updateComboSeq(entity, combo);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }

    /**
     * 放一次超SA(Super Slash Art)。1.1.23 的超SA 由 {@link SuperSlashArts#releaseSSA} 放,它内部自己判
     * 断刀/封印/妖刀+锐利/是否在地面,条件不满足就直接不动作(无返回)。这里先读一遍条件,模型好拿到明确的
     * {@code reason},再放。
     *
     * <p>超SA 的释放要求(和 {@code SuperSlashArts.releaseSSA} 一致):非断刀、非封印、刀同时是妖刀
     * ({@link SwordType#BEWITCHED}=附魔+自定义名或默认妖刀)与锐利边缘({@link SwordType#FIERCEREDGE}=杀敌
     * &ge;1000),且站在地面。
     */
    public static Action superArt(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        if (!(entity instanceof ServerPlayer player)) {
            return new Action(false, "not a player entity");
        }
        EnumSet<SwordType> types = SwordType.from(blade);
        if (blade.getCapability(ItemSlashBlade.BLADESTATE).map(ISlashBladeState::isBroken).orElse(false)) {
            return new Action(false, "the blade is broken; repair it first");
        }
        if (blade.getCapability(ItemSlashBlade.BLADESTATE).map(ISlashBladeState::isSealed).orElse(false)) {
            return new Action(false, "the blade is sealed");
        }
        if (!types.contains(SwordType.BEWITCHED) || !types.contains(SwordType.FIERCEREDGE)) {
            return new Action(false, "the blade needs to be bewitched (enchanted + named) and fierce-edged "
                    + "(1000+ kills) to release its super slash art");
        }
        if (!entity.onGround()) {
            return new Action(false, "release it while on the ground");
        }
        SuperSlashArts.releaseSSA(player);
        return new Action(true, "");
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

    private static String normalize(String type) {
        return type == null ? "normal" : type.toLowerCase(Locale.ROOT);
    }

    /** 这把刀实际的剑技 key(完整注册名)。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }
}
