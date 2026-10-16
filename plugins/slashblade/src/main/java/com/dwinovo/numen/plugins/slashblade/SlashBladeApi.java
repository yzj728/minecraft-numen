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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code slashblade.blade}:拔刀剑——认出拔刀剑、读它的状态、主动挥刀。
 *
 * <p>全在服务端。认刀、读状态是只读;挥刀走拔刀剑自己的 {@code AttackManager.doSlash},伤害由
 * 拔刀剑的管线结算,伤人那一下由 {@code numen.fight.attack} 那条战斗路照常把关(近战接管在
 * {@code AttackCompanionTask.tickWeapon} 里,权限层已经放行才轮到挥刀)。
 *
 * <p><b>什么时候用哪条路</b>:点名打谁、自卫,照旧 {@code numen.fight.attack} / {@code numen.fight.clear}
 * ——主手是拔刀剑时挥出去的就是拔刀剑的招式,不用额外做任何事;{@code slashblade.blade.*} 是
 * 只读识别 + 无目标/演示挥刀的动作级口子,一次一下。
 *
 * <p><b>和史诗战斗的区别</b>:拔刀剑是它自己的模组(攻击管线、断刀、诛仙技能都归它),史诗战斗是
 * 另一套动作系统。两套互不相干;拿拔刀剑打时不需要切史诗战斗模式,拿史诗战斗武器时也不需要碰
 * 这里。车万女仆把拔刀剑当近战武器直接挥,本插件照抄她那条 {@code AttackManager.doSlash} 路径。
 */
public final class SlashBladeApi {

    private SlashBladeApi() {}

    public static void install(NumenApi numen) {
        numen.api("blade", "SlashBlade: recognize a slash blade, read its state, swing it.", SlashBladeApi.class);
    }

    /** 一次挥刀的返回。 */
    @Doc("What one slash did.")
    public record Action(@Doc("Whether the slash went through.") boolean executed,
                         @Doc("Why it did not, when it did not — act on this, do not retry blindly.")
                         String reason) {}

    /** 背包里一把拔刀剑。 */
    @Doc("One inventory slot holding a slash blade.")
    public record Carried(@Doc("The inventory slot (0-8 hotbar, 9-35 rest).") int slot,
                          @Doc("The item id.") String item,
                          @Doc("Whether the blade is broken.") boolean broken) {}

    /** 主手是不是拔刀剑、断没断。 */
    @Fn("What the item in your main hand is, as SlashBlade reads it.")
    @Example("slashblade.blade.held()")
    @Note("held=false means the item in your main hand is not a slash blade (your hand may be empty).")
    @Note("broken=true means the blade is in its damaged state and should be repaired.")
    @SeeAlso({"slashblade.blade.all", "slashblade.blade.swing"})
    public static Carried held(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack stack = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(stack)) {
            return new Carried(-1, "", false);
        }
        return new Carried(0, idOf(stack), SlashBlade.isBroken(stack));
    }

    /** 背包里所有的拔刀剑。 */
    @Fn("Every inventory slot holding a slash blade.")
    @Example("for _, b in ipairs(slashblade.blade.all()) do print(b.slot, b.item, b.broken) end")
    @Note("Use it to know which slash blades you actually carry.")
    @SeeAlso({"slashblade.blade.held", "numen.inv"})
    public static List<Carried> all(ServerCall call) {
        NumenPlayer her = call.her();
        List<Carried> out = new ArrayList<>();
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            ItemStack stack = her.getInventory().getItem(slot);
            if (SlashBlade.isSlashBlade(stack)) {
                out.add(new Carried(slot, idOf(stack), SlashBlade.isBroken(stack)));
            }
        }
        return out;
    }

    /** 挥一下主手的拔刀剑(照拔刀剑自己的攻击管线)。 */
    @Fn("Swing the slash blade in your main hand, through SlashBlade's own attack pipeline.")
    @Example("slashblade.blade.swing()")
    @Note("One slash per call. It does not aim or travel: face the target first (numen.move / numen.fight.attack "
            + "handles its own positioning).")
    @Note("For an actual fight, keep using numen.fight.attack — when your main hand is a slash blade, the melee "
            + "strike goes through SlashBlade automatically; you do not need to call this.")
    @SeeAlso({"slashblade.blade.held", "numen.fight.attack"})
    public static Action swing(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack blade = her.getMainHandItem();
        if (!SlashBlade.isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        boolean ok = SlashBlade.swing(her, blade);
        return new Action(ok, ok ? "" : "slash blade refused the swing (mid-action or unavailable)");
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }
}
