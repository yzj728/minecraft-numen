package com.dwinovo.numen.plugins.epicfight;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.gameasset.EpicFightSkills;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;

/**
 * 本插件对接 Epic Fight(史诗战斗)的那一面——<b>唯一</b>知道 Epic Fight 类型的地方,别处(API、技能、入口)都不碰它的类。
 *
 * <h2>为什么可以直接引用它的类</h2>
 * Epic Fight 开源(GPL-3.0)且类名不混淆,所以直接编译依赖它,拿真正的
 * {@code PlayerPatch}/{@code CapabilityItem}/{@code SkillContainer} 驱动同伴。运行时那份由玩家
 * 自己装上的模组提供({@code compileOnly}),本插件不携带它一个字节。
 *
 * <h2>版本适配:1.20.1 的 20.14.x(以 20.14.17 编译)</h2>
 * <ul>
 *   <li>战斗模式是 {@code PlayerPatch.PlayerMode}(VANILLA/EPICFIGHT),经 {@code toMode(PlayerMode, boolean)}
 *       切换;{@code synchronize=true} 时 {@code ServerPlayerPatch} 会给看得见她的客户端发模式同步包,
 *       第三人称渲染按模式换姿势。</li>
 *   <li>技能执行走 {@code SkillContainer.requestCasting / requestHold / requestCancel}——这是 20.9+ 的
 *       公开口子,内部自己做 {@code canUse} 检查(状态、资源、冷却)并把动画包发给跟踪客户端,和真人按键
 *       走的是同一条服务器路径。</li>
 *   <li>基础攻击、翻滚、格挡分别是 {@code SkillSlots.BASIC_ATTACK / DODGE / GUARD} 三个容器里的
 *       {@code EpicFightSkills.BASIC_ATTACK / ROLL / GUARD}。</li>
 * </ul>
 * <p>上面这些口子在 20.14.x 线上稳定(20.14.8–20.14.17),本插件只 {@code compileOnly} 依赖,
 * 运行期用的就是玩家装的那一份,不必逐个小版本重新编译。</p>
 *
 * <h2>同伴身上为什么自然有这些能力</h2>
 * Epic Fight 按 {@code EntityType.PLAYER} 挂 {@code ServerPlayerPatch}(见它的 {@code EntityPatchProvider}),
 * 同伴是 {@code ServerPlayer} 的假玩家,入世时自动获得补丁;体力数据在 {@code Player} 的静态初始化与
 * {@code defineSynchedData} 里由它的 mixin 定义,同样自然就有。翻滚/格挡技能原版生存玩家要读技能书学,
 * 同伴是机器人,首次用的时候直接授进容器。
 */
public final class EpicFight {

    private EpicFight() {}

    /** 一件物品在 Epic Fight 眼里的样子。 */
    public record Weapon(boolean recognized, String category, String style) {}

    /** 一次动作的结果:做了没有;没做成是什么原因。 */
    public record Action(boolean executed, String reason) {}

    /** 同伴的 ServerPlayerPatch;没装 Epic Fight 或没挂上能力时 null。 */
    public static ServerPlayerPatch patchOf(Player player) {
        return EpicFightCapabilities.getEntityPatch(player, ServerPlayerPatch.class);
    }

    /** 此刻的战斗模式:"battle" 或 "vanilla"。 */
    public static String modeOf(PlayerPatch<?> patch) {
        return patch.isEpicFightMode() ? "battle" : "vanilla";
    }

    /** 切换战斗模式,并同步给所有看得见她的客户端。 */
    public static void setMode(PlayerPatch<?> patch, boolean battle) {
        patch.toMode(battle ? PlayerPatch.PlayerMode.EPICFIGHT : PlayerPatch.PlayerMode.VANILLA, true);
    }

    public static float stamina(PlayerPatch<?> patch) {
        return patch.getStamina();
    }

    public static float maxStamina(PlayerPatch<?> patch) {
        return patch.getMaxStamina();
    }

    /** 格挡此刻是不是开着。 */
    public static boolean guarding(PlayerPatch<?> patch, SkillContainer guard) {
        Skill skill = guard == null ? null : guard.getSkill();
        return skill != null && guard.isActivated() && patch.isHoldingSkill(skill);
    }

    /**
     * 一件物品是不是 Epic Fight 认得的武器、什么类别、什么握法。
     * 认得的判据是 {@code getItemStackCapability} 返回的不是 {@code CapabilityItem.EMPTY}——没挂能力、
     * 空手都是 {@code recognized=false},哪怕它是一把原版剑的样式也照实说。补丁不在场时(没装 Epic Fight
     * 或还没挂上)照给类别、握法留空,不硬算。
     */
    public static Weapon weaponOf(ItemStack stack, PlayerPatch<?> patch) {
        CapabilityItem cap = EpicFightCapabilities.getItemStackCapability(stack);
        if (cap.isEmpty()) {
            return new Weapon(false, "", "");
        }
        String category = cap.getWeaponCategory() == null ? "" : cap.getWeaponCategory().toString();
        String style = "";
        if (patch != null) {
            var s = cap.getStyle(patch);
            style = s == null ? "" : s.toString();
        }
        return new Weapon(true, category, style);
    }

    /** 基础攻击:按手上武器打出一段攻击动画,连续调用就是连击。要先在战斗模式。 */
    public static Action attack(Player player) {
        ServerPlayerPatch patch = patchOf(player);
        if (patch == null) {
            return new Action(false, "epic fight is not installed or has no player patch");
        }
        if (!patch.isEpicFightMode()) {
            return new Action(false, "not in battle mode; epicfight.fight.mode(\"battle\") first");
        }
        SkillContainer container = patch.getSkill(SkillSlots.BASIC_ATTACK);
        if (container == null) {
            return new Action(false, "no basic-attack skill slot");
        }
        grantIfMissing(container, EpicFightSkills.BASIC_ATTACK);
        return cast(container, patch);
    }

    /** 翻滚:朝前方(forward=true)或后方(forward=false)翻滚躲闪。要先在战斗模式。 */
    public static Action dodge(Player player, boolean forward) {
        ServerPlayerPatch patch = patchOf(player);
        if (patch == null) {
            return new Action(false, "epic fight is not installed or has no player patch");
        }
        if (!patch.isEpicFightMode()) {
            return new Action(false, "not in battle mode; epicfight.fight.mode(\"battle\") first");
        }
        SkillContainer container = patch.getSkill(SkillSlots.DODGE);
        if (container == null || EpicFightSkills.ROLL == null) {
            return new Action(false, "the roll dodge skill is unavailable");
        }
        grantIfMissing(container, EpicFightSkills.ROLL);
        // 照游戏客户端同款的参数格式:方向(0=前滚,1=后滚)+ 朝向角
        FriendlyByteBuf args = new FriendlyByteBuf(Unpooled.buffer());
        args.writeInt(forward ? 0 : 1);
        args.writeFloat(player.getYRot());
        return cast(container, patch, args);
    }

    /** 格挡:举起武器挡正面近战(on=false 放下)。要先在战斗模式,手上要有能格挡的武器。 */
    public static Action guard(Player player, boolean on) {
        ServerPlayerPatch patch = patchOf(player);
        if (patch == null) {
            return new Action(false, "epic fight is not installed or has no player patch");
        }
        if (!patch.isEpicFightMode()) {
            return new Action(false, "not in battle mode; epicfight.fight.mode(\"battle\") first");
        }
        SkillContainer container = patch.getSkill(SkillSlots.GUARD);
        if (container == null || EpicFightSkills.GUARD == null) {
            return new Action(false, "the guard skill is unavailable");
        }
        grantIfMissing(container, EpicFightSkills.GUARD);
        FriendlyByteBuf args = new FriendlyByteBuf(Unpooled.buffer());
        if (on) {
            if (!container.requestHold(patch, args)) {
                return new Action(false, "cannot guard right now: no guardable weapon in hand, no stamina, or mid-action");
            }
        } else {
            if (!guarding(patch, container)) {
                return new Action(false, "not guarding right now; nothing to lower");
            }
            container.requestCancel(patch, args);
        }
        return new Action(true, "");
    }

    /** 技能没学过就补进容器:同伴是机器人,翻滚/格挡直接授,不像生存玩家要读技能书。 */
    private static void grantIfMissing(SkillContainer container, Skill skill) {
        if (skill != null && container.isEmpty()) {
            container.setSkill(skill);
        }
    }

    /** 施放一段技能:内部做 canUse 检查(状态、资源)并给跟踪客户端发动画包。 */
    private static Action cast(SkillContainer container, ServerPlayerPatch patch) {
        return cast(container, patch, new FriendlyByteBuf(Unpooled.buffer()));
    }

    private static Action cast(SkillContainer container, ServerPlayerPatch patch, FriendlyByteBuf args) {
        if (container.isEmpty()) {
            return new Action(false, "the skill is not available on this character");
        }
        if (!container.requestCasting(patch, args)) {
            return new Action(false, "cannot do it right now: busy animation, no stamina, or the state does not "
                    + "allow it — wait a moment and try again");
        }
        return new Action(true, "");
    }
}
