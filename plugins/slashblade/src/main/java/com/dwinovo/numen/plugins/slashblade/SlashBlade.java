package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.slasharts.CircleSlash;
import mods.flammpfeil.slashblade.slasharts.Drive;
import mods.flammpfeil.slashblade.slasharts.JudgementCut;
import mods.flammpfeil.slashblade.slasharts.SakuraEnd;
import mods.flammpfeil.slashblade.slasharts.WaveEdge;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

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
 * {@code gradle.properties} 的 {@code slashblade_version=1.1.23-1.20.1} 对着整合包实际在用的那一版编。这一代的
 * 剑技视觉<b>不是客户端动画,而是服务端生成实体</b>——判断斩是 {@code EntityJudgementCut}(它内部再吐
 * {@code EntitySlashEffect} 刀光),drive 系是 {@code EntityDrive},sakura/circle 是 {@code EntitySlashEffect}。
 * 这些实体都是 {@code Projectile} 子类、走 {@code NetworkHooks} 同步、在 {@code tick()} 里自己 {@code areaAttack}
 * 结算伤害。所以只要在服务端把实体生成出来,<b>特效和伤害就一起发生</b>,假玩家(ServerPlayer)与真玩家看到的
 * 完全一样,不依赖假玩家有没有客户端。
 *
 * <h2>程序化搓招表(不让大模型想怎么搓)</h2>
 * 每个 SA 的"招式"就是调它对应的 helper 静态方法(照 {@code ComboStateRegistry} 里那些 tickAction 抄的原样
 * 参数)。这里把它收成一张 {@link #ARTS},key 是 SA 的短名({@code getSlashArtsKey().getPath()}),值是"放这一招
 * 该调什么"。模型只喊一个高层意图({@code slashblade.blade.art()}),服务端按表直调,不经过 combo 异步 tick,
 * 延迟和出错面都小。这样也<b>天然适配不同的拔刀剑</b>:刀上附什么 SA 就查表放什么招。
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
     * 每把拔刀剑的剑技(SA)短名 → 放这一招的动作。
     *
     * <p>这些是 {@code ComboStateRegistry} 里判断斩/樱花/sakura 等 SA 的 {@code tickAction}/{@code clickAction}
     * 原样抄下来的"这一招该生成什么实体",参数与 1.1.23 源码头寸不差。关键:<b>只生成实体,不推进 combo</b>——
     * 实体自己结算伤害、自己同步渲染,不需要 combo 状态机在异步 tick 里推。判断斩比较特殊,它有两档:
     * {@code normal} 用多目标收刀({@code doJudgementCutSuper},"完整判断斩"),{@code just} 用单目标强化
     * ({@code doJudgementCutJust});其余 SA 只有一档,{@code just} 与 {@code normal} 同招。
     */
    private static final Map<String, BiConsumer<LivingEntity, Boolean>> ARTS = Map.of(
            "judgement_cut", (u, just) -> {
                if (just) {
                    JudgementCut.doJudgementCutJust(u);
                } else {
                    JudgementCut.doJudgementCutSuper(u);
                }
            },
            "sakura_end", (u, just) -> {
                SakuraEnd.doSlash(u, 22.5F, Vec3.ZERO, false, false, 1.2);
                SakuraEnd.doSlash(u, 180F - 22.5F, Vec3.ZERO, false, true, 1.5);
            },
            "void_slash", (u, just) -> AttackManager.doVoidSlashAttack(u),
            "circle_slash", (u, just) -> {
                CircleSlash.doCircleSlashAttack(u, 180);
                CircleSlash.doCircleSlashAttack(u, 90);
                CircleSlash.doCircleSlashAttack(u, 0);
                CircleSlash.doCircleSlashAttack(u, -90);
            },
            "drive_vertical", (u, just) -> Drive.doSlash(u, -90F, 10, Vec3.ZERO, false, 7, 2f),
            "drive_horizontal", (u, just) -> Drive.doSlash(u, 0F, 10, Vec3.ZERO, false, 7, 2f),
            "wave_edge", (u, just) -> WaveEdge.doSlash(u, 90F, 20, Vec3.ZERO, false, 7, 0.2f, 1f, 4));

    /**
     * 放一次剑技——查搓招表直调对应 helper,生成特效实体。
     *
     * <p>断刀/封印时拔刀剑自己的实体也不会生效,但这里先挡一道、给出明确的失败原因。特效实体的伤害与资源消耗
     * (骄傲之魂等)由拔刀剑的实体 tick 结算——本插件不越俎代庖,也不双重结算({@code EntityJudgementCut} 等
     * 已经在 {@code tick()} 里 {@code areaAttack})。挥一下手臂让动作看起来连贯。
     */
    private static Art doArt(LivingEntity entity, ISlashBladeState state, String type) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        String key = shortName(state.getSlashArtsKey());
        BiConsumer<LivingEntity, Boolean> action = ARTS.get(key);
        if (action == null) {
            return new Art(false, artOf(state), "this blade's slash art is not supported");
        }
        boolean just = "just".equals(normalize(type));
        action.accept(entity, just);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }

    private static String normalize(String type) {
        return type == null ? "normal" : type.toLowerCase(Locale.ROOT);
    }

    /** 这把刀实际的剑技 key(完整注册名,如 {@code slashblade:judgement_cut})。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }

    /** SA 注册 key 的短名(去掉名字空间前缀),作搓招表的键;没有则回退判断斩。 */
    private static String shortName(ResourceLocation key) {
        if (key == null) {
            return "judgement_cut";
        }
        return key.getPath();
    }
}
