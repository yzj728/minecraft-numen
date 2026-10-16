package com.dwinovo.numen.plugins.slashblade;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code slashblade.blade}:拔刀剑——认出拔刀剑、读它的状态与剑技、主动挥刀、放剑技(SA/超SA)、打一整套连击。
 *
 * <p>全在服务端。认刀、读状态是只读;挥刀走拔刀剑自己的 {@code AttackManager.doSlash},放剑技走拔刀剑自己的
 * {@code SlashArts.doArts} + {@code ComboState} 时间线(见 {@link SlashBlade}),完整的多段刀光/位移/收刀由
 * {@link SlashBladeTicker} 逐 tick 驱动,伤害、特效与资源消耗(骄傲之魂等)都由拔刀剑自己的实体结算。伤人那一下
 * 由 {@code numen.fight.attack} 那条战斗路照常把关;{@code art}/{@code combo} 是只影响同伴自己动作的技能释放,
 * 不直接指定打谁(它朝面朝方向放),伤害落到谁由拔刀剑结算。
 *
 * <p><b>什么时候用哪条路</b>:点名打谁、自卫,照旧 {@code numen.fight.attack} / {@code numen.fight.clear}
 * ——主手是拔刀剑时普通挥击自动走拔刀剑管线(带冷却自动搓随机档位的招),不用额外做任何事;{@code art} 是
 * 主动放剑技的口子(指定档次或随机),{@code combo} 是主动打一整套连击。
 *
 * <p><b>预制招式、不用搓</b>:剑技的全部动作、连段与特效由服务端预置,而且<b>不经过玩家的蓄力判定</b>——
 * {@code art("normal"|"just"|"super")} 直接放对应档的完整一招,{@code art("random")} 由服务端随机挑一个能放的
 * 档(能超SA 就有一半概率超SA,否则普通/完美各半)。完美版(just)进来就是完美版,不用掐那零点几秒。模型不要
 * 自己编排挥刀序列。
 *
 * <p><b>版本</b>:编译对着拔刀剑 1.1.23(整合包实际在用),适配 1.1.x 线。
 */
public final class SlashBladeApi {

    private SlashBladeApi() {}

    public static void install(NumenApi numen) {
        numen.api("blade", "SlashBlade: recognize a slash blade, read its state and its slash art, swing it, "
                + "release its slash art (normal/just/super/random) or a full combo.", SlashBladeApi.class);
    }

    /** 一次普通挥刀、放超剑技或一套连击的返回。 */
    @Doc("What one plain slash, super slash art or full combo did.")
    public record Action(@Doc("Whether the slash went through.") boolean executed,
                         @Doc("Why it did not, when it did not — act on this, do not retry blindly.")
                         String reason) {}

    /** 一次剑技(SA)的返回。 */
    @Doc("What one slash art did.")
    public record Art(@Doc("Whether the slash art went through.") boolean executed,
                      @Doc("The slash art key of the blade in your hand, e.g. slashblade:judgement_cut.")
                      String sa,
                      @Doc("Why it did not, when it did not — act on this, do not retry blindly.")
                      String reason) {}

    /** 放哪一个档的剑技。 */
    public record ArtSpec(@Doc("\"normal\", \"just\", \"super\" or \"random\". random lets the server pick; "
            + "defaults to \"random\".")
                          String type) {}

    /** 背包里一把拔刀剑。 */
    @Doc("One inventory slot holding a slash blade.")
    public record Carried(@Doc("The inventory slot (0-8 hotbar, 9-35 rest; -1 when not in hand).") int slot,
                          @Doc("The item id.") String item,
                          @Doc("Whether the blade is broken.") boolean broken,
                          @Doc("The blade's slash art (SA) key, e.g. slashblade:judgement_cut. Empty when not a "
                                  + "slash blade.")
                          String sa) {}

    /** 主手是不是拔刀剑、断没断、附什么剑技。 */
    @Fn("What the item in your main hand is, as SlashBlade reads it, including its slash art.")
    @Example("slashblade.blade.held()")
    @Note("held=false means the item in your main hand is not a slash blade (your hand may be empty).")
    @Note("broken=true means the blade is in its damaged state and should be repaired before fighting.")
    @Note("sa is the key of the slash art attached to this blade — slashblade.blade.art() releases exactly this.")
    @SeeAlso({"slashblade.blade.all", "slashblade.blade.swing", "slashblade.blade.art"})
    public static Carried held(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack stack = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(stack)) {
            return new Carried(-1, "", false, "");
        }
        return new Carried(0, idOf(stack), SlashBlade.isBroken(stack), SlashBlade.artKey(stack));
    }

    /** 背包里所有的拔刀剑。 */
    @Fn("Every inventory slot holding a slash blade, with its state and slash art.")
    @Example("for _, b in ipairs(slashblade.blade.all()) do print(b.slot, b.item, b.broken, b.sa) end")
    @Note("Use it to know which slash blades you actually carry and what slash art each one has.")
    @SeeAlso({"slashblade.blade.held", "numen.inv"})
    public static List<Carried> all(ServerCall call) {
        NumenPlayer her = call.her();
        List<Carried> out = new ArrayList<>();
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            ItemStack stack = her.getInventory().getItem(slot);
            if (SlashBlade.isSlashBlade(stack)) {
                out.add(new Carried(slot, idOf(stack), SlashBlade.isBroken(stack), SlashBlade.artKey(stack)));
            }
        }
        return out;
    }

    /** 挥一下主手的拔刀剑(照拔刀剑自己的攻击管线,普通挥刀)。 */
    @Fn("Swing the slash blade in your main hand, through SlashBlade's own attack pipeline.")
    @Example("slashblade.blade.swing()")
    @Note("One plain slash per call. It does not aim or travel: face the target first.")
    @Note("For an actual fight, keep using numen.fight.attack — when your main hand is a slash blade, the melee "
            + "strike goes through SlashBlade automatically (with cooldown it even releases a random slash art).")
    @SeeAlso({"slashblade.blade.held", "slashblade.blade.art", "numen.fight.attack"})
    public static Action swing(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack blade = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        boolean ok = SlashBlade.swing(her, blade);
        return new Action(ok, ok ? "" : "slash blade refused the swing (mid-action or unavailable)");
    }

    /** 放一次剑技(SA),指定档次或随机。 */
    @Fn("Release the slash art (SA) of the slash blade in your main hand, as one complete pre-made move.")
    @Example("slashblade.blade.art()")
    @Example("slashblade.blade.art(\"just\")")
    @Example("slashblade.blade.art(\"super\")")
    @Note("type: \"normal\" is the usual release, \"just\" the perfect (just-timed) release, \"super\" the super "
            + "slash art (needs a bewitched + fierce-edged blade at full durability, on the ground), \"random\" "
            + "lets the server pick whatever the blade can do. Defaults to \"random\".")
    @Note("It is pre-made: no timing window to hit. \"just\" comes out as the perfect version every time, "
            + "\"super\" comes out every time the blade qualifies. You never need to time a charge.")
    @Note("The full move — multiple slash waves, movement, sheathing, hitstun — plays out over the next few ticks "
            + "server-side. Do NOT script the swings yourself.")
    @Note("It does not aim for you: face the target first. When it fails, read the reason.")
    @SeeAlso({"slashblade.blade.held", "slashblade.blade.combo", "numen.fight.attack"})
    public static Art art(ServerCall call, ArtSpec args) {
        NumenPlayer her = call.her();
        ItemStack blade = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        String type = args == null || args.type() == null ? "random" : args.type().toLowerCase(Locale.ROOT);
        if (!type.equals("normal") && !type.equals("just") && !type.equals("super") && !type.equals("random")) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "type must be \"normal\", \"just\", \"super\" or \"random\"", null);
        }
        SlashBlade.Art done = SlashBlade.art(her, blade, type);
        return new Art(done.executed(), done.sa(), done.reason());
    }

    /** 打一整套连击(随机起手连段)。 */
    @Fn("Start a full pre-made combo — a random grounded string (basic slash string or rapid-slash) or the aerial "
            + "string, played out in full server-side.")
    @Example("slashblade.blade.combo()")
    @Note("The whole string — its chain of slash waves, movement and sheathing — advances automatically each tick. "
            + "You call it once and it plays out; do not call it every tick.")
    @Note("Face the target first; it does not aim for you.")
    @SeeAlso({"slashblade.blade.art", "slashblade.blade.swing", "numen.fight.attack"})
    public static Action combo(ServerCall call) {
        NumenPlayer her = call.her();
        SlashBlade.Action done = SlashBlade.combo(her, her.getMainHandItem());
        return new Action(done.executed(), done.reason());
    }

    /** 放一次超剑技(超SA)。 */
    @Fn("Release the super slash art of the slash blade in your main hand.")
    @Example("slashblade.blade.super_art()")
    @Note("This needs a blade that is both bewitched (enchanted + custom-named) and fierce-edged (1000+ kills), "
            + "unbroken, unsealed, at full durability, and you must be on the ground.")
    @Note("Check first; when it fails, read the reason and do not retry blindly.")
    @SeeAlso({"slashblade.blade.held", "slashblade.blade.art", "numen.fight.attack"})
    public static Action superArt(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack blade = her.getMainHandItem();
        SlashBlade.Action done = SlashBlade.superArt(her, blade);
        return new Action(done.executed(), done.reason());
    }

    /** 主手这把刀附的特殊效果(SE)。 */
    @Fn("The special effects (SE) attached to the slash blade in your main hand.")
    @Example("slashblade.blade.se()")
    @Note("SE are passive effects attached to a blade, not moves you actively cast. In 1.1.23 there is only one "
            + "built-in (slashblade:wither_edge); an empty list means this blade has none.")
    @SeeAlso({"slashblade.blade.held", "slashblade.blade.all"})
    public static List<String> se(ServerCall call) {
        NumenPlayer her = call.her();
        return SlashBlade.specialEffects(her.getMainHandItem());
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }
}
