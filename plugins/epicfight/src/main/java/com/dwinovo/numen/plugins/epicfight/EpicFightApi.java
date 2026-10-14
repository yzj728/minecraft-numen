package com.dwinovo.numen.plugins.epicfight;

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
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code epicfight.fight}:史诗战斗——认武器、切模式、挥剑连击、翻滚躲闪、举武器格挡。
 *
 * <p>全在服务端。所有动作都经 Epic Fight 自己的服务器执行口({@code requestCasting} 那一族)走,
 * 与真人按键同一条路径:它自己检查状态与体力、扣资源、给看得见她的客户端发动画包——第三人称看到
 * 的就是真的史诗战斗动作。改的只是同伴自己的模式与动作,不碰世界、不碰别人;伤人那一下由史诗战斗
 * 的伤害管线结算,权限层由 {@code numen.fight.attack} 那条战斗路照常把关。
 *
 * <p><b>什么时候用哪条路</b>:点名打谁、自卫,照旧 {@code numen.fight.attack} / {@code numen.fight.clear}
 * (战斗系统自己走位、自己打,战斗模式下的挥击一样生效);{@code epicfight.fight.*} 是动作级控制——演示、
 * 切磋、躲技能、摆架势,一次一个动作。
 */
public final class EpicFightApi {

    private EpicFightApi() {}

    public static void install(NumenApi numen) {
        numen.api("fight", "Epic Fight combat: read a weapon's category and style, switch between vanilla and "
                + "battle mode, attack with battle-mode animations, roll to dodge, guard.", EpicFightApi.class);
    }

    /** 一件物品在 Epic Fight 眼里的样子。 */
    @Doc("One item as Epic Fight reads it.")
    public record Weapon(@Doc("Whether Epic Fight recognizes this item (sword, axe, spear, bow, …). Bare hands "
            + "are not a weapon: false.")
                         boolean recognized,
                         @Doc("Epic Fight's weapon category: SWORD, AXE, SPEAR, UCHIGATANA, GREATSWORD, LONGSWORD, "
                                 + "DAGGER, BOW, CROSSBOW, SHIELD, … Empty when not recognized.")
                         String category,
                         @Doc("How it is held: ONE_HAND, TWO_HAND, SHEATH, … Empty when not recognized.")
                         String style) {}

    /** 现况。 */
    @Doc("The current Epic Fight state.")
    public record Status(@Doc("Whether Epic Fight is installed and patched onto you.") boolean installed,
                         @Doc("Your combat mode: \"battle\" (Epic Fight) or \"vanilla\" (normal Minecraft).")
                         String mode,
                         @Doc("Current stamina, 0 to maxStamina.") double stamina,
                         @Doc("Your maximum stamina.") double maxStamina,
                         @Doc("Whether you are currently guarding.") boolean guarding,
                         @Doc("What Epic Fight reads from the item in your main hand.") Weapon mainHand) {}

    /** 背包里一格史诗战斗武器。 */
    @Doc("One inventory slot holding an Epic Fight weapon.")
    public record Carried(@Doc("The inventory slot (0-8 hotbar, 9-35 rest).") int slot,
                          @Doc("The item id.") String item,
                          @Doc("What Epic Fight reads from it.") Weapon weapon) {}

    /** 切哪个模式。 */
    public record Mode(@Doc("\"battle\" for Epic Fight combat mode, \"vanilla\" for normal Minecraft combat.")
                       String mode) {}

    /** 朝哪滚。 */
    public record Direction(@Doc("\"forward\" rolls toward where you face, \"backward\" rolls away from the enemy "
            + "in front of you.")
                            String direction) {}

    /** 举/放格挡。 */
    public record Guard(@Doc("true raises the guard, false lowers it.") boolean on) {}

    /** 一次动作的结果。 */
    @Doc("What one action did.")
    public record Action(@Doc("Whether the action went through.") boolean executed,
                         @Doc("Why it did not, when it did not — act on this, do not retry blindly.")
                         String reason) {}

    /**
     * 现况:装了没有、什么模式、体力、是否在格挡、主手是什么武器。只读。
     */
    @Fn("Your Epic Fight state: installed, mode, stamina, guard, and the weapon in your main hand.")
    @Example("epicfight.fight.status()")
    @Note("Read-only. mode is \"battle\" or \"vanilla\"; stamina is what dodge and guard cost.")
    @SeeAlso({"epicfight.fight.mode", "epicfight.fight.weapon"})
    public static Status status(ServerCall call) {
        NumenPlayer her = call.her();
        ServerPlayerPatch patch = EpicFight.patchOf(her);
        if (patch == null) {
            return new Status(false, "vanilla", 0.0, 0.0, false, new Weapon(false, "", ""));
        }
        SkillContainer guard = patch.getSkill(SkillSlots.GUARD);
        return new Status(true, EpicFight.modeOf(patch), patch.getStamina(), patch.getMaxStamina(),
                EpicFight.guarding(patch, guard), EpicFight.weaponOf(her.getMainHandItem(), patch));
    }

    /** 主手是什么武器。 */
    @Fn("What Epic Fight reads from the item in your main hand: category and style.")
    @Example("epicfight.fight.weapon()")
    @Note("recognized=false means the held item is not an Epic Fight weapon (or your hand is empty).")
    @SeeAlso({"epicfight.fight.weapons", "epicfight.fight.status"})
    public static Weapon weapon(ServerCall call) {
        NumenPlayer her = call.her();
        return EpicFight.weaponOf(her.getMainHandItem(), EpicFight.patchOf(her));
    }

    /** 背包里所有的史诗战斗武器。 */
    @Fn("Every inventory slot holding an item Epic Fight recognizes, with its category and style.")
    @Example("for _, w in ipairs(epicfight.fight.weapons()) do print(w.slot, w.item, w.weapon.category) end")
    @Note("Use it to know which weapon you actually carry and what moves it gets in battle mode.")
    @SeeAlso({"epicfight.fight.weapon", "numen.inv"})
    public static List<Carried> weapons(ServerCall call) {
        NumenPlayer her = call.her();
        List<Carried> out = new ArrayList<>();
        for (int slot = 0; slot < her.getInventory().getContainerSize(); slot++) {
            ItemStack stack = her.getInventory().getItem(slot);
            EpicFight.Weapon w = EpicFight.weaponOf(stack, EpicFight.patchOf(her));
            if (w.recognized()) {
                out.add(new Carried(slot, idOf(stack), w));
            }
        }
        return out;
    }

    /**
     * 切战斗模式。battle = 史诗战斗(武器有动作、能翻滚格挡、怪也用史诗战斗招式);vanilla = 原版。
     */
    @Fn("Switch your combat mode: \"battle\" (Epic Fight) or \"vanilla\" (normal Minecraft).")
    @Example("epicfight.fight.mode(\"battle\")")
    @Example("epicfight.fight.mode(\"vanilla\")")
    @Note("Only your own mode changes; the owner and other creatures keep theirs.")
    @Note("Stay in vanilla when shooting TACZ guns — guns do not belong to Epic Fight.")
    @Note("In battle mode your melee fights (numen.fight.attack) still work; Epic Fight handles the damage "
            + "pipeline, and epicfight.fight.attack adds the real battle-mode moves.")
    @SeeAlso({"epicfight.fight.status", "epicfight.fight.attack", "tacz.gun.shoot"})
    public static String mode(ServerCall call, Mode args) {
        NumenPlayer her = call.her();
        ServerPlayerPatch patch = EpicFight.patchOf(her);
        if (patch == null) {
            throw new ApiError(ErrorKind.FAILED, "epic fight is not installed", null);
        }
        String want = args.mode() == null ? "" : args.mode().toLowerCase(Locale.ROOT);
        boolean battle;
        if ("battle".equals(want)) {
            battle = true;
        } else if ("vanilla".equals(want) || "normal".equals(want)) {
            battle = false;
        } else {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "mode must be \"battle\" or \"vanilla\"", null);
        }
        EpicFight.setMode(patch, battle);
        return EpicFight.modeOf(patch);
    }

    /** 挥一下:按手上武器打出史诗战斗的攻击动作。 */
    @Fn("Throw one attack with the weapon in your main hand, using Epic Fight's battle animations.")
    @Example("epicfight.fight.attack()")
    @Note("One swing per call; call it again for the next hit of the combo. Works only in battle mode.")
    @Note("It does not aim or travel: face the target first (numen.move / numen.fight.attack handles its own "
            + "positioning).")
    @SeeAlso({"epicfight.fight.mode", "epicfight.fight.dodge", "numen.fight.attack"})
    public static Action attack(ServerCall call) {
        return EpicFight.attack(call.her());
    }

    /** 翻滚躲闪。 */
    @Fn("Roll forward or backward to dodge — Epic Fight's dodge move.")
    @Example("epicfight.fight.dodge(\"forward\")")
    @Example("epicfight.fight.dodge(\"backward\")")
    @Note("Costs stamina; when you are out, it fails and says so. Works only in battle mode.")
    @SeeAlso({"epicfight.fight.status", "epicfight.fight.attack"})
    public static Action dodge(ServerCall call, Direction args) {
        String direction = args.direction() == null ? "" : args.direction().toLowerCase(Locale.ROOT);
        boolean forward = !"backward".equals(direction);
        if (!"forward".equals(direction) && !"backward".equals(direction)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "direction must be \"forward\" or \"backward\"", null);
        }
        return EpicFight.dodge(call.her(), forward);
    }

    /** 举武器格挡 / 放下。 */
    @Fn("Raise or lower your weapon guard — Epic Fight's block.")
    @Example("epicfight.fight.guard(true)")
    @Example("epicfight.fight.guard(false)")
    @Note("Blocks melee hits from the front. Needs a guardable weapon in hand and battle mode.")
    @Note("Lower it when the fight pauses — holding it does nothing by itself.")
    @SeeAlso({"epicfight.fight.status", "epicfight.fight.attack"})
    public static Action guard(ServerCall call, Guard args) {
        return EpicFight.guard(call.her(), args.on());
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }
}
