package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方。
 *
 * <h2>核心:直接生成拔刀剑的刀光特效实体,不碰 ComboState 状态机</h2>
 * 平A 之所以有刀光+伤害,是因为走 {@link AttackManager#doSlash}:它生成一个 {@code EntitySlashEffect} 实体
 * ({@code new EntitySlashEffect(...) + setOwner + setDamage + addFreshEntity}),实体自己 tick 结算伤害。
 * 之前试过「切到 {@code ComboState}、由 ticker 逐刻推 {@code tickAction}」那条路,在假玩家(ServerPlayer)身上
 * 放不出多段招式——假玩家没有女仆(NPC)那种独立的 AI goal 去持续推进连段状态机。所以这里<b>彻底绕开
 * ComboState</b>,所有招式都用「直接 {@code doSlash}」——和平A 同一条、已验证可用的实体生成链路。
 *
 * <h2>各招式的实现:不同 roll 角度 / comboRatio / 位移组合</h2>
 * 拔刀剑的「招式」视觉本质就是一连串 {@code EntitySlashEffect} 刀光。这里按招式名编排几刀
 * {@code doSlash}(不同 roll 角度扭出多段刀光)+ 给身体一个位移,模拟疾走居合的前冲、跳跃攻击的升空。
 * 伤害由每一刀各自结算,和平A 完全一致。SA 的独特多目标特效(判断斩/樱花等)这里用多段刀光逼近,
 * 不依赖那些可能因版本差异签不对的 helper 类。
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

    /** 挥一下拔刀剑(普通挥击)——就是平A 那一下,已验证有刀光+伤害。 */
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

    /** 这把刀附加的剑技(SA)的注册 key;没有则回退判断斩。 */
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

    /** 这把刀附的特殊效果(SE)的注册 key 列表。SE 是被动,不是主动放的招。 */
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
     * 放一次剑技(SA)。{@code type} = {@code normal}/{@code just}/{@code super}/{@code random}。
     * <p>直接生成多段刀光(不同 roll 角度),伤害由每段各自结算。{@code just} 加暴击、{@code super} 加暴击
     * 和更大力度。带目标时先面向它。
     */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        return art(entity, blade, type, null);
    }

    public static Art art(LivingEntity entity, ItemStack blade, String type, Entity target) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        if (blade.getCapability(ItemSlashBlade.BLADESTATE).map(ISlashBladeState::isBroken).orElse(false)) {
            return new Art(false, artKey(blade), "the blade is broken; repair it first");
        }
        if (blade.getCapability(ItemSlashBlade.BLADESTATE).map(ISlashBladeState::isSealed).orElse(false)) {
            return new Art(false, artKey(blade), "the blade is sealed");
        }
        if (target != null) {
            entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        }
        String t = type == null ? "random" : type.toLowerCase(Locale.ROOT);
        boolean just = false;
        boolean powerful = false;
        switch (t) {
            case "just" -> just = true;
            case "super" -> powerful = true;
            case "random" -> {
                if (entity.getRandom().nextBoolean()) just = true;
            }
            default -> { /* normal */ }
        }
        releaseArt(entity, just, powerful);
        return new Art(true, artKey(blade), "");
    }

    /** 放一次 SA 的实体效果:多段刀光(判断斩式的连续斩杀)。 */
    private static void releaseArt(LivingEntity entity, boolean just, boolean powerful) {
        RandomSource random = entity.getRandom();
        double ratio = powerful ? 1.2 : just ? 0.88 : 1.0;
        int count = powerful ? 5 : just ? 4 : 3;
        for (int i = 0; i < count; i++) {
            float roll = -60 + (i * 40) + (random.nextFloat() * 20 - 10);
            // 每一段都是一道独立刀光,伤害由 EntitySlashEffect 自己结算
            AttackManager.doSlash(entity, roll, Vec3.ZERO, false, just || powerful, ratio, KnockBacks.smash);
        }
        entity.swing(InteractionHand.MAIN_HAND);
    }

    /** 放一次超SA 的别名(暴击高力度多段)。 */
    public static Action superArt(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        releaseArt(entity, true, true);
        return new Action(true, "");
    }

    /**
     * 直接释放一个具体招式,完全跳过搓招/ComboState。
     * {@code move} = {@code rapid_slash}(疾走居合)、{@code upperslash}(挑空)、{@code upperslash_jump}(跃升斩)、
     * {@code aerial_cleave}(空中下劈)、{@code rising_star}(晨星)。
     */
    public static Action release(LivingEntity entity, ItemStack blade, String move) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        String m = move == null ? "" : move.toLowerCase(Locale.ROOT);
        switch (m) {
            case "rapid_slash" -> releaseRapidSlash(entity);
            case "upperslash" -> releaseUpperSlash(entity, false);
            case "upperslash_jump" -> releaseUpperSlash(entity, true);
            case "aerial_cleave" -> releaseAerialCleave(entity);
            case "rising_star" -> releaseRisingStar(entity);
            default -> {
                return new Action(false, "unknown move: " + move
                        + " (known: rapid_slash, upperslash, upperslash_jump, aerial_cleave, rising_star)");
            }
        }
        entity.swing(InteractionHand.MAIN_HAND);
        return new Action(true, "");
    }

    /** 疾走居合:前冲 + 连续多段刀光。 */
    private static void releaseRapidSlash(LivingEntity entity) {
        // 前冲位移(朝她面朝方向)
        Vec3 look = entity.getLookAngle();
        entity.setDeltaMovement(look.x * 0.8, entity.getDeltaMovement().y, look.z * 0.8);
        randSlashes(entity, 5, 0.7);
    }

    /** 挑空 / 跃升斩:挑空(或升空)+ 上挥刀光。 */
    private static void releaseUpperSlash(LivingEntity entity, boolean jump) {
        if (jump) {
            entity.setDeltaMovement(0, 0.6, 0);
            entity.setOnGround(false);
            entity.hasImpulse = true;
        }
        AttackManager.doSlash(entity, -80, Vec3.ZERO, false, false, 0.5, KnockBacks.toss);
        if (jump) {
            AttackManager.doSlash(entity, -57, Vec3.ZERO, false, false, 0.6, KnockBacks.toss);
        }
    }

    /** 空中下劈:向下的重击刀光(正面落点)。 */
    private static void releaseAerialCleave(LivingEntity entity) {
        AttackManager.doSlash(entity, 55, Vec3.ZERO, true, true, 0.44, KnockBacks.meteor);
    }

    /** 晨星:上挑连击(多段上行刀光)。 */
    private static void releaseRisingStar(LivingEntity entity) {
        entity.setDeltaMovement(0, 0.6, 0);
        entity.setOnGround(false);
        entity.hasImpulse = true;
        AttackManager.doSlash(entity, -57, Vec3.ZERO, false, false, 0.6, KnockBacks.toss);
        randSlashes(entity, 3, 0.5);
    }

    /** 随机角度的几段刀光。 */
    private static void randSlashes(LivingEntity entity, int count, double ratio) {
        RandomSource random = entity.getRandom();
        for (int i = 0; i < count; i++) {
            float roll = -90 + 180 * random.nextFloat();
            AttackManager.doSlash(entity, roll, AttackManager.genRushOffset(entity), false, i % 2 == 0, ratio,
                    KnockBacks.smash);
        }
    }

    /** 放一整套连击(预制):随机起手,但都落地成「多段刀光」,不依赖 ComboState。 */
    public static Action combo(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        if (entity.onGround()) {
            randSlashes(entity, 4, 0.55);
        } else {
            releaseAerialCleave(entity);
        }
        entity.swing(InteractionHand.MAIN_HAND);
        return new Action(true, "");
    }

    /** 这把刀实际的剑技 key(完整注册名)。 */
    private static String artOf(ISlashBladeState state) {
        ResourceLocation key = state.getSlashArtsKey();
        return key == null ? "slashblade:judgement_cut" : key.toString();
    }
}
