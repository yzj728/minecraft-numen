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
import net.minecraft.util.RandomSource;
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
 * <h2>预制招式:绕开蓄力,按「档」取招</h2>
 * 真人放 SA 要长按右键、按蓄力时长判「普通/完美/失败」,完美窗口只有 3~4 tick,连玩家都常打不出来。
 * 但程序<b>不需要经过那条蓄力判定</b>:{@link SlashArts#doArts} 是按 {@code ArtsType}(Success/Jackpot/Super)
 * 直接返回目标 combo 的纯函数。所以这里直接按想要的档位取 combo、{@link ISlashBladeState#updateComboSeq}
 * 切过去,再由 ticker 逐 tick 播完整条时间线——<b>要普通有普通、要完美有完美、要超SA有超SA,零蓄力、零随机
 * 失手</b>。AI 只喊一个高层指令({@code art("normal"|"just"|"super"|"random")}),服务端照档取招。
 *
 * <h2>程序化搓招(不让大模型想怎么搓)</h2>
 * 剑技的全部动作、连段与特效由服务端预置。{@link #art} 放单招(指定档次或随机),{@link #combo} 放一整套
 * 连击(随机起手连段 + 随机 SA 收尾)。模型不要自己编排挥刀序列——那样延迟大还容易乱。
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
        if (!basic) {
            return false;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> !state.isBroken() && state.getDamage() == 0)
                .orElse(false);
    }

    /**
     * 放一次剑技(SA)。{@code type} 是 {@code normal} / {@code just} / {@code super} / {@code random} 之一。
     *
     * <p><b>完全预制、绕开蓄力</b>:直接经 {@link SlashArts#doArts} 按档位拿 combo、{@code updateComboSeq} 切过去,
     * 之后多段由 {@link SlashBladeTicker} 逐 tick 播完。{@code random} 时服务端按刀状态随机挑一个能放的档次
     * (能放超SA 就有一半概率放超SA,否则普通/完美随机)。要完美版({@code just})进来就是完美版,不必掐时机。
     */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            return doArt(entity, state, resolveType(entity, blade, type));
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    /** 把 {@code type} 规范成一个 {@link SlashArts.ArtsType};{@code random} 时按刀状态随机。 */
    private static SlashArts.ArtsType resolveType(LivingEntity entity, ItemStack blade, String type) {
        String t = type == null ? "random" : type.toLowerCase(Locale.ROOT);
        RandomSource random = entity.getRandom();
        return switch (t) {
            case "normal" -> SlashArts.ArtsType.Success;
            case "just" -> SlashArts.ArtsType.Jackpot;
            case "super" -> canSuper(blade, entity) ? SlashArts.ArtsType.Super : SlashArts.ArtsType.Success;
            case "random" -> {
                // 能放超SA 就掷一枚硬币决定放不放,放不出超SA 时普通/完美各半
                if (canSuper(blade, entity) && random.nextBoolean()) {
                    yield SlashArts.ArtsType.Super;
                }
                yield random.nextBoolean() ? SlashArts.ArtsType.Success : SlashArts.ArtsType.Jackpot;
            }
            default -> SlashArts.ArtsType.Success;
        };
    }

    /** 放一次剑技——先判断刀/封印/是否附魔,再按已定档位切 combo。 */
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
        ResourceLocation combo = state.getSlashArts().doArts(artsType, entity);
        if (combo == null || combo.equals(ComboStateRegistry.NONE.getId())) {
            return new Art(false, artOf(state), "this blade's slash art is not supported");
        }
        state.updateComboSeq(entity, combo);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }

    /** 放一次超SA的别名——等价于 {@code art(..., "super")},保留显式口子。 */
    public static Action superArt(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        if (!canSuper(blade, entity)) {
            return new Action(false, "the blade needs to be bewitched (enchanted + named), fierce-edged "
                    + "(1000+ kills), unbroken, unsealed, at full durability, and you on the ground");
        }
        if (!(entity instanceof ServerPlayer player)) {
            return new Action(false, "not a player entity");
        }
        SuperSlashArts.releaseSSA(player);
        return new Action(true, "");
    }

    /**
     * 放一整套连击(预制),由服务端随机选套路:先随机进一个普攻连段起手,再由 ticker 逐 tick 推完整条连段,
     * 到收招时随机接一个 SA 收尾。模型只喊 {@code slashblade.blade.combo()},不用自己想套路。
     */
    public static Action combo(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        RandomSource random = entity.getRandom();
        // 起手连段(1.1.23 的 standby 输入表映射):地面用普攻连段 A 或疾走居合,空中用空中连段。
        ResourceLocation opener = entity.onGround()
                ? (random.nextBoolean() ? ComboStateRegistry.COMBO_A1.getId() : ComboStateRegistry.RAPID_SLASH.getId())
                : ComboStateRegistry.AERIAL_CLEAVE.getId();
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            state.updateComboSeq(entity, opener);
            entity.swing(InteractionHand.MAIN_HAND);
            // 收尾 SA 由 ticker 推进连段到终点后随机接;这里只记一个"已起手",真正收尾见 SlashBladeTicker
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
