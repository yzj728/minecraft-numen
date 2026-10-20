package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.concentrationrank.CapabilityConcentrationRank;
import mods.flammpfeil.slashblade.capability.concentrationrank.IConcentrationRank;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.JudgementCut;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import mods.flammpfeil.slashblade.util.AttackManager;
import mods.flammpfeil.slashblade.util.KnockBacks;
import mods.flammpfeil.slashblade.util.VectorHelper;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方。
 *
 * <h2>招式池 + 随机出招 + 瞬移接近 + 幻影剑</h2>
 * 拔刀剑自己的连段状态机是「帧数超时 + next/nextOfTimeout 网点」自动推进的,那套东西为真玩家的
 * 按键节奏设计,对假玩家(没有真实按键时序)完全不可控。因此这里不依赖网点推进,改由 Numen 在若干招式之间
 * 随机挑,每招用 {@code updateComboSeq} 显式切入。招式池覆盖地面连段、挑空、疾走居合、空中下劈、空中连段、晨星。
 *
 * <p><b>瞬移接近</b>:目标够不着时,先瞬移到它身边——空中目标保持在它同高处走空中连段,地面目标落到它附近的地面。
 *
 * <p><b>幻影剑</b>:照车万女仆「真正的力量」{@code BASE_SUMMONED_SWORD}——直接生成 {@link EntityAbstractSummonedSword}
 * 射向目标,遵守原版消耗(妖刀 + 力量箭附魔 + 骄傲之魂)。
 *
 * <p>「判断斩」这类 SA 的特效在 {@code ComboState.tickAction} 里靠 ticker 逐刻驱动,而假玩家的 ticker 时序对不齐,
 * 于是切过去「后退、收刀都出了,唯独召唤次元斩特效没出」。修法:放 SA 后对「判断斩类 SA」直接调原版 {@link JudgementCut}
 * helper 立即生成次元斩实体,不等 ticker。
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

    /** 这一刻评价是否到 SSS——原版放超SA的门槛,不白嫖:评价自然涨跌,到了才放得出来。 */
    private static boolean isSssRank(LivingEntity entity) {
        return entity.getCapability(CapabilityConcentrationRank.RANK_POINT)
                .map(rank -> rank.getRank(entity.level().getGameTime()) == IConcentrationRank.ConcentrationRanks.SSS)
                .orElse(false);
    }

    /** 能不能放超级 SA:评价 SSS + 妖 + 锐利边缘 + 非断刀 + 非封印 + 满耐久 + 站地面。 */
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
        if (!isSssRank(entity)) {
            return false;   // 评价没到 SSS 放不出超SA——不白嫖,评价自然涨
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE)
                .map(state -> !state.isBroken() && state.getDamage() == 0)
                .orElse(false);
    }

    /**
     * 附近最近的「看得见」的敌对生物,锁定半径与近战警戒半径一致({@code 10} 格)。
     * 找不到返回 null。优先最近、够不着的不锁——锁一个墙后远方的目标只会徒劳。
     */
    public static LivingEntity nearestTarget(LivingEntity entity) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        double reach = 10.0;
        for (LivingEntity other : entity.level().getEntitiesOfClass(LivingEntity.class,
                entity.getBoundingBox().inflate(reach), e -> true)) {
            if (other == entity || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            boolean hostile = other instanceof Enemy
                    || (other instanceof Mob mob && mob.getTarget() == entity)
                    || other.getLastHurtByMob() == entity;
            if (!hostile) {
                continue;
            }
            double d = entity.distanceToSqr(other);
            if (d > reach * reach) {
                continue;
            }
            if (!entity.hasLineOfSight(other)) {
                continue;
            }
            if (d < bestDist) {
                best = other;
                bestDist = d;
            }
        }
        return best;
    }

    // ==================== 招式池 ====================

    /** 普通招式池,随机抽一个(不含 SA;SA 由调用方另按概率决定)。 */
    private static ResourceLocation randomMove(LivingEntity entity, RandomSource rng) {
        int r = rng.nextInt(6);
        return switch (r) {
            case 0 -> ComboStateRegistry.COMBO_A1.getId();
            case 1 -> ComboStateRegistry.UPPERSLASH.getId();
            case 2 -> ComboStateRegistry.RAPID_SLASH.getId();
            case 3 -> ComboStateRegistry.AERIAL_CLEAVE.getId();
            case 4 -> ComboStateRegistry.AERIAL_RAVE_A1.getId();
            default -> ComboStateRegistry.RISING_STAR.getId();
        };
    }

    /** 近战够不着的距离阈值:超过它,先瞬移接近,而不是原地空挥。 */
    private static final double RUSH_THRESHOLD = 5.0;

    /**
     * 随机切入一个普通招式。目标够不着时先瞬移到它身边(空中→空中连段,地面→落脚点)。
     *
     * @return false = 这一拍没切(刀不是拔刀剑),调用方走兜底。
     */
    public static boolean advanceCombo(LivingEntity entity, ItemStack blade, LivingEntity target, RandomSource rng) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        ResourceLocation loc;
        if (entity.distanceTo(target) > RUSH_THRESHOLD) {
            // 远了先瞬移贴上去
            if (!teleportToTarget(entity, blade, target)) {
                return false;
            }
            loc = ComboStateRegistry.RAPID_SLASH.getId();
        } else {
            loc = randomMove(entity, rng);
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            state.updateComboSeq(entity, loc);
            entity.swing(InteractionHand.MAIN_HAND);
            return true;
        }).orElse(false);
    }

    /**
     * 瞬移到目标身边,随后打空中/地面连段。
     *
     * <p><b>目标在空中</b>(飞行、被挑空、未落地)→ 瞬移到它同高处,走空中连段({@code AERIAL_RAVE_A1});
     * <b>目标在地面</b> → 从它脚下往下找落脚点,瞬移过去(不落进虚空、不卡进方块),走地面连段。
     *
     * @return 是否真的瞬移了(fasle = 找不到安全的落脚点,别瞬移)
     */
    public static boolean teleportToTarget(LivingEntity entity, ItemStack blade, LivingEntity target) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        if (!target.onGround() && !target.isInWater()) {
            // 空中敌人:瞬移到它身边同高处,留给空中连段
            entity.teleportTo(target.getX(), target.getY(), target.getZ());
            entity.setDeltaMovement(Vec3.ZERO);
            entity.fallDistance = 0.0f;
            blade.getCapability(ItemSlashBlade.BLADESTATE)
                    .ifPresent(state -> state.updateComboSeq(entity, ComboStateRegistry.AERIAL_RAVE_A1.getId()));
            entity.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        // 地面目标:找它附近能站住的落脚点(从上往下扫,第一格能站住就落)
        BlockPos landing = findLanding(entity.level(), target.blockPosition());
        if (landing == null) {
            return false;   // 找不到安全落脚点,不瞬移
        }
        entity.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0f;
        return true;
    }

    /**
     * 从 {@code center} 往下/原地找一格「身上和脚上都是空气」的落脚点,找不到返回 null。
     * 简单可靠:向下扫 8 格,找到第一个「下面实心、上面两格空」的 y。
     */
    private static BlockPos findLanding(Level level, BlockPos center) {
        for (int dy = 0; dy <= 8; dy++) {
            BlockPos at = center.above(dy);
            BlockPos below = at.below();
            if (level.getBlockState(at).isAir()
                    && level.getBlockState(at.above()).isAir()
                    && !level.getBlockState(below).isAir()) {
                return at;
            }
        }
        // 原地(目标脚下)本身是空气就落原地
        for (int dy = 0; dy >= -8; dy--) {
            BlockPos at = center.above(dy);
            BlockPos below = at.below();
            if (level.getBlockState(at).isAir()
                    && level.getBlockState(at.above()).isAir()
                    && !level.getBlockState(below).isAir()) {
                return at;
            }
        }
        return null;
    }

    /**
     * 放一次 SA。评价够放超SA(SSS),否则普通 SA——评价自然涨跌,不再锁死。
     * 对「判断斩类 SA」额外直调 {@link JudgementCut} helper 立即补特效。
     */
    public static boolean trySlashArts(LivingEntity entity, ItemStack blade, LivingEntity target) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            if (state.isBroken() || state.isSealed()) {
                return false;
            }
            if (!SwordType.from(blade).contains(SwordType.ENCHANTED)) {
                return false;
            }
            state.setTargetEntityId(target.getId());
            boolean sup = canSuper(blade, entity);
            SlashArts.ArtsType type = sup ? SlashArts.ArtsType.Super : SlashArts.ArtsType.Success;
            ResourceLocation comboLoc = state.getSlashArts().doArts(type, entity);
            ComboState combo = ComboStateRegistry.REGISTRY.get().getValue(comboLoc);
            if (combo == null || Objects.equals(comboLoc, ComboStateRegistry.NONE.getId())) {
                return false;
            }
            state.updateComboSeq(entity, comboLoc);
            entity.swing(InteractionHand.MAIN_HAND);
            patchJudgementCutArt(entity, blade, sup);
            return true;
        }).orElse(false);
    }

    /** 对「判断斩类」SA 补特效:普通/完美 = 后退+球状次元斩,超 = 48 格锁定次元斩。非判断斩不动作。 */
    private static void patchJudgementCutArt(LivingEntity entity, ItemStack blade, boolean sup) {
        String key = artKey(blade);
        if (key == null || !key.endsWith("judgement_cut")) {
            return;
        }
        if (sup) {
            JudgementCut.doJudgementCutSuper(entity);
        } else {
            JudgementCut.doJudgementCut(entity);
        }
    }

    /**
     * 发一发幻影剑射向目标。照车万女仆「真正的力量」{@code BASE_SUMMONED_SWORD}。
     *
     * <p><b>遵守原版消耗</b>:妖刀(BEWITCHED)、带力量箭附魔(POWER_ARROWS)、骄傲之魂够扣——
     * 任一不满足就不发(返回 false),退回普通挥击。这不白嫖资源。
     */
    public static boolean summonSword(LivingEntity entity, ItemStack blade, LivingEntity target) {
        if (!isSlashBlade(blade) || target == null || !target.isAlive()) {
            return false;
        }
        if (!SwordType.from(blade).contains(SwordType.BEWITCHED)) {
            return false;   // 妖刀才放得出幻影剑
        }
        if (blade.getEnchantmentLevel(Enchantments.POWER_ARROWS) <= 0) {
            return false;   // 力量箭附魔决定幻影剑伤害来源
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            if (state.isBroken() || state.isSealed()) {
                return false;
            }
            if (state.getProudSoulCount() < 1) {
                return false;   // 骄傲之魂是幻影剑的消耗
            }
            state.setProudSoulCount(state.getProudSoulCount() - 1);

            Level world = entity.level();
            int powerLevel = blade.getEnchantmentLevel(Enchantments.POWER_ARROWS);
            Vec3 targetPos = new Vec3(target.getX(), target.getY() + target.getEyeHeight() * 0.5, target.getZ());

            EntityAbstractSummonedSword sword = new EntityAbstractSummonedSword(
                    mods.flammpfeil.slashblade.SlashBlade.RegistryEvents.SummonedSword, world);
            Vec3 pos = entity.getEyePosition(1.0f)
                    .add(VectorHelper.getVectorForRotation(0.0f, entity.getViewYRot(0) + 90)
                            .scale(world.random.nextDouble() > 0.5 ? 1 : -1));
            sword.setPos(pos.x, pos.y, pos.z);
            sword.setDamage(powerLevel);
            sword.setOwner(entity);
            sword.setColor(state.getColorCode());
            sword.setRoll(entity.getRandom().nextFloat() * 360.0f);
            Vec3 dir = targetPos.subtract(pos).normalize();
            sword.shoot(dir.x, dir.y, dir.z, 3.0f, 0.0f);
            world.addFreshEntity(sword);
            entity.playSound(SoundEvents.CHORUS_FRUIT_TELEPORT, 0.2F, 1.45F);
            return true;
        }).orElse(false);
    }

    /** 此刻连段是否还没播完(当前档位的下一段不是 none,说明这招还有后续、正在播)。 */
    public static boolean isBusy(LivingEntity entity, ItemStack blade) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            ResourceLocation cur = state.resolvCurrentComboState(entity);
            if (cur.equals(ComboStateRegistry.NONE.getId())) {
                return false;
            }
            ComboState current = ComboStateRegistry.REGISTRY.get().getValue(cur);
            if (current == null) {
                return false;
            }
            return !current.getNext(entity).equals(ComboStateRegistry.NONE.getId());
        }).orElse(false);
    }

    // ==================== 动作级口子 ====================

    /** 放一次剑技(SA)。{@code type} = normal/just/super/auto/random。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        return art(entity, blade, type, nearestTarget(entity));
    }

    /** 放一次剑技,带目标。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type, Entity target) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        if (target != null) {
            entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        }
        String t = type == null ? "auto" : type.toLowerCase(Locale.ROOT);
        if ("auto".equals(t)) {
            SlashArts.ArtsType arts = canSuper(blade, entity) ? SlashArts.ArtsType.Super : SlashArts.ArtsType.Success;
            return blade.getCapability(ItemSlashBlade.BLADESTATE)
                    .map(state -> doArt(entity, state, arts, target))
                    .orElse(new Art(false, "", "the slash blade has no blade state"));
        }
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

    /** 放一次剑技:先补锁定,再 doArts → updateComboSeq。判断斩类补 helper 特效。评价不锁,自然涨跌。 */
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
        // 判断斩类补特效
        patchJudgementCutArt(entity, entity.getMainHandItem(), artsType == SlashArts.ArtsType.Super);
        return new Art(true, artOf(state), "");
    }

    /** 放一次超SA 的别名。 */
    public static Action superArt(LivingEntity entity, ItemStack blade) {
        SlashBlade.Art done = art(entity, blade, "super");
        return new Action(done.executed(), done.reason());
    }

    /** 直接释放一个具体招式(疾走居合/挑空/跃升斩/空中下劈/晨星),doSlash 直调。 */
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

    /** 疾走居合:瞬移式前冲 + 连续多段刀光。 */
    private static void releaseRapidSlash(LivingEntity entity) {
        Vec3 look = entity.getLookAngle();
        Vec3 target = entity.position().add(look.x * 3.0, 0, look.z * 3.0);
        entity.teleportTo(target.x, entity.getY(), target.z);
        entity.setDeltaMovement(look.x * 1.2, entity.getDeltaMovement().y, look.z * 1.2);
        for (int i = 0; i < 6; i++) {
            AttackManager.doSlash(entity, -40F + 80F * i / 5f, Vec3.ZERO, false, i % 2 == 0, 0.7, KnockBacks.toss);
        }
    }

    /** 挑空 / 跃升斩。 */
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

    /** 空中下劈。 */
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

    /** 逐 tick 推进刀当前 combo 时间线。由 SlashBladeTicker 调用。 */
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
