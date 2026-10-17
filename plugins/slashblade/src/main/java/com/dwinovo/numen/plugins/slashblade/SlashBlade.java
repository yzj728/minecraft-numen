package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方。
 *
 * <h2>核心:用拔刀剑原生 {@code doArts} 按「刀当前附的 SA」放招</h2>
 * 所有 SA(原版 / star_fantasy / slashblade_addon / murasame)都是统一接口:
 * <pre>
 *   state.getSlashArts().doArts(type, entity) → combo key → state.updateComboSeq(entity, combo)
 * </pre>
 * 后台不需要知道「这刀是什么 SA」,也不硬编码映射。
 *
 * <h2>释放前置:锁定目标(关键)</h2>
 * 很多 SA(后撤飞剑 / 聚怪次元炸 / 召唤机器人戳 / 次元斩)的触发前提是「刀有一个<b>已锁定目标</b>」
 * ({@code state.setTargetEntityId})。真人靠「潜行键」锁定,假玩家没有那个动作,所以放招前先锁定。
 * 没显式给目标时,{@link #nearestTarget} 自动找「够得着、看得见」的最近敌对生物,避免空挥。
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

    /** 挥一下拔刀剑(普通挥击)。 */
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

    /** 能不能放超级 SA:妖 + 锐利边缘 + 非断刀 + 非封印 + 满耐久 + 站地面。 */
    private static boolean canSuper(ItemStack blade, LivingEntity entity) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        EnumSet<SwordType> types = SwordType.from(blade);
        boolean basic = types.contains(SwordType.BEWITCHED) && types.contains(SwordType.FIERCEREDGE)
                && !types.contains(SwordType.SEALED);
        if (!basic || !entity.onGround()) {
            return false;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> !state.isBroken() && state.getDamage() == 0)
                .orElse(false);
    }

    /**
     * 附近最近的「够得着、看得见」的敌对生物,作为 SA 的锁定目标。假玩家不会按潜行锁定,放招前用这个补目标。
     * 找不到返回 null——宁可空(不空挥),也不锁一个打不到的。
     */
    public static LivingEntity nearestTarget(LivingEntity entity) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        double reach = 8.0;   // SA 的作用范围(后撤飞剑/次元斩都能到这一圈)
        for (LivingEntity other : entity.level().getEntitiesOfClass(LivingEntity.class,
                entity.getBoundingBox().inflate(reach), this::wantsToFight)) {
            if (other == entity || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            double d = entity.distanceToSqr(other);
            if (d > reach * reach) {
                continue;   // 太远了够不着,别空挥
            }
            if (!entity.hasLineOfSight(other)) {
                continue;   // 隔着墙看不见,别空挥
            }
            if (d < bestDist) {
                best = other;
                bestDist = d;
            }
        }
        return best;
    }

    /** 这个目标值不值得打:敌对怪物,或者在打她/她刚打过。 */
    private static boolean wantsToFight(LivingEntity e) {
        return e instanceof Enemy || e.getLastHurtByMob() == null;
    }

    /** 放一次剑技(SA)。{@code type} = normal/just/super/random。不带目标时自动找最近敌对生物。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        return art(entity, blade, type, nearestTarget(entity));
    }

    /** 放一次剑技,带目标——放之前面向目标 + 锁定目标。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type, Entity target) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        if (target != null) {
            entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        }
        String t = type == null ? "random" : type.toLowerCase(Locale.ROOT);
        if ("super".equals(t)) {
            if (!canSuper(blade, entity)) {
                return new Art(false, artKey(blade),
                        "the blade needs to be bewitched (enchanted + named), fierce-edged (1000+ kills), "
                                + "unbroken, unsealed, at full durability, and on the ground");
            }
            return blade.getCapability(ItemSlashBlade.BLADESTATE)
                    .map(state -> doArt(entity, state, SlashArts.ArtsType.Super, target))
                    .orElse(new Art(false, "", "the slash blade has no blade state"));
        }
        SlashArts.ArtsType artsType;
        if ("random".equals(t)) {
            if (canSuper(blade, entity) && entity.getRandom().nextBoolean()) {
                return blade.getCapability(ItemSlashBlade.BLADESTATE)
                        .map(state -> doArt(entity, state, SlashArts.ArtsType.Super, target))
                        .orElse(new Art(false, "", "the slash blade has no blade state"));
            }
            artsType = entity.getRandom().nextBoolean() ? SlashArts.ArtsType.Jackpot : SlashArts.ArtsType.Success;
        } else if ("just".equals(t)) {
            artsType = SlashArts.ArtsType.Jackpot;
        } else {
            artsType = SlashArts.ArtsType.Success;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            return doArt(entity, state, artsType, target);
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    /**
     * 放一次剑技:先给刀设置锁定目标(很多 SA 的前置),再 doArts 取 combo → updateComboSeq 切过去。
     */
    private static Art doArt(LivingEntity entity, ISlashBladeState state, SlashArts.ArtsType artsType, Entity target) {
        if (state.isBroken()) {
            return new Art(false, artOf(state), "the blade is broken; repair it first");
        }
        if (state.isSealed()) {
            return new Art(false, artOf(state), "the blade is sealed");
        }
        if (!SwordType.from(entity.getMainHandItem()).contains(SwordType.ENCHANTED)) {
            return new Art(false, artOf(state), "this blade is not enchanted; it has no slash art yet");
        }
        // 锁定目标:很多 SA(后撤飞剑/聚怪次元炸/召唤物戳)的触发前提。
        if (target != null) {
            state.setTargetEntityId(target.getId());
        }
        ResourceLocation comboLoc = state.getSlashArts().doArts(artsType, entity);
        ComboState combo = ComboStateRegistry.REGISTRY.get().getValue(comboLoc);
        if (combo == null || Objects.equals(comboLoc, ComboStateRegistry.NONE.getId())) {
            return new Art(false, artOf(state), "this blade's slash art is not supported");
        }
        state.updateComboSeq(entity, comboLoc);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artOf(state), "");
    }

    /** 放一次超SA 的别名——= 终极 SA(超级变式,按当前刀)。 */
    public static Action superArt(LivingEntity entity, ItemStack blade) {
        SlashBlade.Art done = art(entity, blade, "super");
        return new Action(done.executed(), done.reason());
    }

    /**
     * 直接释放一个具体招式,完全跳过搓招/ComboState。
     * {@code move} = rapid_slash(疾走居合)/upperslash(挑空)/upperslash_jump(跃升斩)/aerial_cleave(空中下劈)/rising_star(晨星)。
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

    /** 疾走居合:瞬移式前冲(朝面朝方向)+ 连续多段刀光。 */
    private static void releaseRapidSlash(LivingEntity entity) {
        Vec3 look = entity.getLookAngle();
        Vec3 target = entity.position().add(look.x * 3.0, 0, look.z * 3.0);
        entity.teleportTo(target.x, entity.getY(), target.z);
        entity.setDeltaMovement(look.x * 1.2, entity.getDeltaMovement().y, look.z * 1.2);
        for (int i = 0; i < 6; i++) {
            AttackManager.doSlash(entity, -40F + 80F * i / 5f, Vec3.ZERO, false, i % 2 == 0, 0.7, KnockBacks.toss);
        }
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

    /** 空中下劈:向下的重击刀光。 */
    private static void releaseAerialCleave(LivingEntity entity) {
        AttackManager.doSlash(entity, 55, Vec3.ZERO, true, true, 0.44, KnockBacks.meteor);
    }

    /** 晨星:上挑连击。 */
    private static void releaseRisingStar(LivingEntity entity) {
        entity.setDeltaMovement(0, 0.6, 0);
        entity.setOnGround(false);
        entity.hasImpulse = true;
        AttackManager.doSlash(entity, -57, Vec3.ZERO, false, false, 0.6, KnockBacks.toss);
        for (int i = 0; i < 3; i++) {
            AttackManager.doSlash(entity, -57 + 20 * i, Vec3.ZERO, false, true, 0.5, KnockBacks.toss);
        }
    }

    /** 放一整套连击(预制)。 */
    public static Action combo(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return new Action(false, "no slash blade in your main hand");
        }
        RandomSource random = entity.getRandom();
        int slashes = 3 + random.nextInt(3);
        for (int i = 0; i < slashes; i++) {
            AttackManager.doSlash(entity, -45F + 90F * i, Vec3.ZERO, false, i % 2 == 1, 0.7, KnockBacks.smash);
        }
        entity.swing(InteractionHand.MAIN_HAND);
        return new Action(true, "");
    }

    /**
     * 逐 tick 推进刀当前的 combo 时间线——复现 {@code ItemSlashBlade.inventoryTick} 里 {@code cs.tickAction(living)}
     * 那一段,让判断斩这类「tickAction 型」SA 的多段特效能被假玩家放出。由 {@link SlashBladeTicker} 每服务端刻调用。
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
