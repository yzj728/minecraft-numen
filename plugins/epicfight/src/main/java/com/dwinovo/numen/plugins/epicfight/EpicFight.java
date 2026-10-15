package com.dwinovo.numen.plugins.epicfight;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.gameasset.EpicFightSkills;
import yesman.epicfight.skill.Skill;
import yesman.epicfight.skill.SkillContainer;
import yesman.epicfight.skill.SkillSlots;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.player.PlayerPatch;
import yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;

import java.util.List;

/**
 * 本插件对接 Epic Fight(史诗战斗)的那一面——<b>唯一</b>知道 Epic Fight 类型的地方,别处(API、技能、入口)都不碰它的类。
 *
 * <h2>为什么可以直接引用它的类</h2>
 * Epic Fight 开源(GPL-3.0)且类名不混淆,所以直接编译依赖它,拿真正的
 * {@code PlayerPatch}/{@code CapabilityItem}/{@code SkillContainer} 驱动同伴。运行时那份由玩家
 * 自己装上的模组提供({@code compileOnly}),本插件不携带它一个字节。
 *
 * <h2>版本适配:1.20.1 的 20.14.x(以 20.14.17 编译)</h2>
 * 同上,只 {@code compileOnly},运行期用玩家装的那一份。
 *
 * <h2>攻击、翻滚为什么直接放动画,不经技能容器</h2>
 * 技能容器({@code requestCasting})是给<b>真人玩家</b>搓招用的:它查连击计数器、查各种玩家状态。同伴是假玩家,
 * 不是真人、不搓招——照车万女仆(NPC)适配的做法,直接拿武器连击表里的一段动画
 * {@code playAnimationSynchronized} 放出去,伤害由史诗战斗的 {@code AttackAnimation} 攻击帧管线自动结算。
 * 女仆用 {@code MobPatch},我们用 {@code ServerPlayerPatch},两者都继承 {@code LivingEntityPatch}、都有这套口子。
 *
 * <p>格挡例外:它要拦正面伤害({@code GuardSkill} 注册的伤害拦截靠 hold 状态),不是单纯一段动画,所以仍走
 * {@code requestHold}/{@code requestCancel}。
 */
public final class EpicFight {

    private EpicFight() {}

    /** 超过这个距离就放冲刺攻击(DASH),否则放普通连击。与女仆 SWORD_DASH 的 withinDistance(1.0, 2.5) 同量级。 */
    private static final double DASH_DISTANCE = 2.5;

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
     * 认得的判据是 {@code getItemStackCapability} 返回的不是 {@code CapabilityItem.EMPTY}。
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

    /**
     * 直接放一段攻击动画——<b>照女仆 NPC 的逻辑,不经 BASIC_ATTACK 技能容器</b>。
     *
     * <p>连击表结构是 {@code [AUTO1..AUTOn, DASH, AIR_SLASH]}(末尾两段是冲刺与空中攻击)。按距离选招:
     * <ul>
     *   <li>距离 {@code > DASH_DISTANCE}:放 {@code DASH}(倒数第二段)——冲刺接近,和女仆的冲刺技同一层;</li>
     *   <li>否则:放普通连击 {@code AUTO}(第 {@code comboStep % (size-2)} 段),连续调用就是连击。</li>
     * </ul>
     * 伤害由史诗战斗的 {@code AttackAnimation} 攻击帧自动结算,动画包发给所有看得见她的客户端。
     *
     * @param comboStep 连击序号,由调用方维护、每次成功挥击 +1;这里对普通连击段数取模
     */
    public static Action swing(Player player, LivingEntity target, int comboStep) {
        ServerPlayerPatch patch = patchOf(player);
        if (patch == null) {
            return new Action(false, "epic fight is not installed or has no player patch");
        }
        if (!patch.isEpicFightMode()) {
            return new Action(false, "not in battle mode; epicfight.fight.mode(\"battle\") first");
        }
        if (!patch.getEntityState().canBasicAttack()) {
            return new Action(false, "still in the previous attack animation — wait for it to finish");
        }
        CapabilityItem cap = patch.getHoldingItemCapability(InteractionHand.MAIN_HAND);
        List<AnimationAccessor<? extends AttackAnimation>> combo = cap.getAutoAttackMotion(patch);
        if (combo == null || combo.isEmpty()) {
            return new Action(false, "no attack animation for the item in hand");
        }
        int size = combo.size();
        // 普通连击段数 = size - 2(末尾两段固定是 DASH 与 AIR);至少 1,避免长矛单手/太刀收鞘这类只有 1 段普通攻击的武器除零
        int normalSize = Math.max(1, size - 2);
        int dashIndex = size - 2;
        double distance = target == null ? 0.0 : player.distanceTo(target);
        AnimationAccessor<? extends AttackAnimation> motion;
        if (distance > DASH_DISTANCE && size >= 3) {
            // 冲刺攻击:朝目标冲刺(动画自带的坐标属性会追踪目标),并把目标登记给它
            motion = combo.get(dashIndex);
            if (target != null) {
                patch.setAttackTarget(target);
            }
        } else {
            // 普通连击:在普通段里轮换
            motion = combo.get(Math.floorMod(comboStep, normalSize));
        }
        patch.playAnimationSynchronized(motion, 0.0F);
        return new Action(true, "");
    }

    /** 基础攻击:无目标的简单挥击(放第一段连击)。 */
    public static Action attack(Player player) {
        return swing(player, null, 0);
    }

    /**
     * 翻滚躲闪:<b>直接放翻滚动画</b>(照女仆 NPC 直接放动画的逻辑,不经 ROLL 技能容器)。
     * {@code forward=true} 朝前滚,{@code false} 朝后滚。要先在战斗模式。
     */
    public static Action dodge(Player player, boolean forward) {
        ServerPlayerPatch patch = patchOf(player);
        if (patch == null) {
            return new Action(false, "epic fight is not installed or has no player patch");
        }
        if (!patch.isEpicFightMode()) {
            return new Action(false, "not in battle mode; epicfight.fight.mode(\"battle\") first");
        }
        patch.playAnimationSynchronized(
                forward ? Animations.BIPED_ROLL_FORWARD : Animations.BIPED_ROLL_BACKWARD, 0.0F);
        return new Action(true, "");
    }

    /**
     * 格挡:举起武器挡正面近战(on=false 放下)。要先在战斗模式,手上要有能格挡的武器。
     * 仍走技能容器——格挡要拦正面伤害({@code GuardSkill} 的伤害拦截靠 hold 状态),不是单纯一段动画。
     */
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

    /** 技能没学过就补进容器:同伴是机器人,格挡直接授,不像生存玩家要读技能书。 */
    private static void grantIfMissing(SkillContainer container, Skill skill) {
        if (skill != null && container.isEmpty()) {
            container.setSkill(skill);
        }
    }
}
