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
 * {@code slashblade.blade}:拔刀剑——认出拔刀剑、读它的状态与剑技、主动挥刀、放剑技(SA)。
 *
 * <p>全在服务端。认刀、读状态是只读;挥刀走拔刀剑自己的 {@code AttackManager.doSlash},放剑技走本插件内置的
 * 程序化搓招表(见 {@link SlashBlade}),伤害、特效与资源消耗(骄傲之魂等)都由拔刀剑自己的实体结算。伤人那一下
 * 由 {@code numen.fight.attack} 那条战斗路照常把关;{@code art} 是只影响同伴自己动作的技能释放,不直接指定打谁
 * (它朝面朝方向放),伤害落到谁由拔刀剑结算。
 *
 * <p><b>什么时候用哪条路</b>:点名打谁、自卫,照旧 {@code numen.fight.attack} / {@code numen.fight.clear}
 * ——主手是拔刀剑时普通挥击自动走拔刀剑管线,不用额外做任何事;{@code slashblade.blade.art} 是主动放剑技
 * (SA)的动作级口子,一次放完整一招,在想要比普通挥刀更狠、范围更大、或特定效果时用。
 *
 * <p><b>适配不同的拔刀剑</b>:剑技(SA)是每把刀自己的属性——{@code held().sa} / {@code all()[].sa} 告诉你
 * 这把刀附的是什么剑技,{@code art(...)} 读的是当前主手那把刀的 SA,按内置搓招表放对应的一招,所以换哪把刀都放对。
 *
 * <p><b>程序化搓招</b>:剑技的全部动作、连段与特效由服务端预置,模型只喊一个高层指令(放哪一档),不用也不应
 * 自己编排挥刀序列——那样延迟大还容易乱。
 *
 * <p><b>版本</b>:编译对着拔刀剑 1.1.23(整合包实际在用),适配 1.1.x 线。
 *
 * <p><b>和史诗战斗的区别</b>:拔刀剑是它自己的模组(攻击管线、断刀、剑技都归它),史诗战斗是另一套动作系统。
 * 两套互不相干;拿拔刀剑打时不需要切史诗战斗模式,拿史诗战斗武器时也不需要碰这里。
 */
public final class SlashBladeApi {

    private SlashBladeApi() {}

    public static void install(NumenApi numen) {
        numen.api("blade", "SlashBlade: recognize a slash blade, read its state and its slash art, swing it, "
                + "release its slash art.", SlashBladeApi.class);
    }

    /** 一次普通挥刀的返回。 */
    @Doc("What one plain slash did.")
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
    public record ArtSpec(@Doc("\"normal\" for the usual release, \"just\" for the just-timed release. "
            + "Defaults to \"normal\".")
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
    @Note("One plain slash per call. It does not aim or travel: face the target first (numen.move / "
            + "numen.fight.attack handles its own positioning).")
    @Note("For an actual fight, keep using numen.fight.attack — when your main hand is a slash blade, the melee "
            + "strike goes through SlashBlade automatically; you do not need to call this.")
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

    /** 放一次剑技(SA)。 */
    @Fn("Release the slash art (SA) of the slash blade in your main hand, through SlashBlade's own pipeline.")
    @Example("slashblade.blade.art()")
    @Example("slashblade.blade.art(\"just\")")
    @Note("It releases the slash art attached to the blade you hold (see held().sa), so it adapts to whichever "
            + "blade you carry.")
    @Note("\"normal\" is the usual release, \"just\" the just-timed release. Leave type out for \"normal\".")
    @Note("The full move — its animation, effects and combo — is pre-programmed server-side. Call art() and the "
            + "whole art plays; do NOT try to script the swings yourself.")
    @Note("It does not aim for you: face the target first. When it fails, read the reason.")
    @SeeAlso({"slashblade.blade.held", "slashblade.blade.swing", "numen.fight.attack"})
    public static Art art(ServerCall call, ArtSpec args) {
        NumenPlayer her = call.her();
        ItemStack blade = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        String type = args == null || args.type() == null ? "normal" : args.type().toLowerCase(Locale.ROOT);
        if (!type.equals("normal") && !type.equals("just")) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "type must be \"normal\" or \"just\"", null);
        }
        SlashBlade.Art done = SlashBlade.art(her, blade, type);
        return new Art(done.executed(), done.sa(), done.reason());
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }
}
