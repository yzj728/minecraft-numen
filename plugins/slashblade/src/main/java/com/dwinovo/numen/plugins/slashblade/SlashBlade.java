package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
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
 * {@code ISlashBladeState} / 各 SA 的 helper 驱动同伴。运行时那份由玩家自己装上的模组提供({@code compileOnly}),
 * 本插件不携带它一个字节。
 *
 * <h2>版本基准:1.1.23(1.20.1 线,整合包实际在用)</h2>
 * 这一代剑技的视觉是<b>服务端生成实体</b>(判断斩是 {@code EntityJudgementCut},drive 系是 {@code EntityDrive},
 * sakura/circle 是 {@code EntitySlashEffect}),这些实体走 {@code NetworkHooks} 同步、在 {@code tick()} 里自己
 * {@code areaAttack} 结算伤害。所以只要实体在服务端被正确生成,假玩家(ServerPlayer)与真玩家看到的一模一样。
 *
 * <h2>放剑技的两步(缺一不可,这是车万女仆 native-power-of-maid 验证过的完整链路)</h2>
 * <ol>
 *   <li><b>推进 combo 状态</b>:{@link ISlashBladeState#doChargeAction}——真人松右键走的官方入口,它按 SA 与蓄力
 *       时长把刀切到正确的 combo 段,这会顺带把刀的 color/rank 等状态就绪,也结算一次伤害。光有这一步,刀的
 *       combo 状态是对的,但特效实体要等之后的 tick 由 {@code ItemSlashBlade.inventoryTick → cs.tickAction}
 *       才生成——而假玩家没有那条被动 tick 链,所以光这样没特效。</li>
 *   <li><b>补生成特效实体</b>:直接调各 SA 的 helper 静态方法(它们从刀的 state 读 color/rank、设 owner、
 *       {@code addFreshEntity}),把原本该由 inventoryTick 异步生成的特效实体<b>同步生成</b>出来。这样既不走绕过
 *       状态机(颜色/朝向对),又不依赖假玩家的被动 tick。</li>
 * </ol>
 * 上一版错误地被总结成"只调 helper、或只调 doChargeAction 二选一"——两个都调才对:doChargeAction 负责"状态正确",
 * helper 负责"特效实体真的出来"。
 *
 * <h2>程序化搓招(不让大模型想怎么搓)</h2>
 * 各 SA 的"招式"收成一张 {@link #ARTS}(key 是 SA 短名,值是"放这招该调哪个 helper"),模型只喊一个高层指令
 * ({@code slashblade.blade.art()}),服务端照表直调。天然适配不同拔刀剑:刀上附什么 SA 就放什么招。
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
     * 各 SA 短名 → 放招动作(照 {@code ComboStateRegistry} 里 tickAction/clickAction 抄的原样参数)。
     * 判断斩 special:normal 用多目标收刀({@code doJudgementCutSuper}),just 用单目标强化
     * ({@code doJudgementCutJust});其余 SA 只有一档。
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
     * 放一次剑技——两步:先 {@code doChargeAction} 推进 combo 状态(让刀 color/rank 就绪、结算伤害),
     * 再调 helper 同步生成特效实体(原先由 inventoryTick 异步做的)。
     *
     * <p>插招前半段只推进状态、后半段只生成实体,叠加起来才等价于"真玩家放完整一招"。挥一下手臂让动作连贯。
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
        // 第一步:推进 combo 状态(官方入口),让刀 state 就绪并结算一次伤害
        int elapsed = just ? 10 : 20;
        state.doChargeAction(entity, elapsed);
        // 第二步:同步生成特效实体(补假玩家缺失的 inventoryTick 那一环)
        action.accept(entity, just);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }

    private static String normalize(String type) {
        return type == null ? "normal" : type.toLowerCase(Locale.ROOT);
    }

    /** 这把刀实际的剑技 key(完整注册名)。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }

    /** SA 注册 key 短名,作搓招表键;没有回退判断斩。 */
    private static String shortName(ResourceLocation key) {
        if (key == null) {
            return "judgement_cut";
        }
        return key.getPath();
    }
}
