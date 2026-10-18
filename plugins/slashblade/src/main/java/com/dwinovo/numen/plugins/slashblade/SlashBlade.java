package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.concentrationrank.CapabilityConcentrationRank;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.registry.ComboStateRegistry;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.slasharts.JudgementCut;
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
 * <h2>招式轮换 + SA 特效补丁</h2>
 * 拔刀剑自己的连段状态机是「帧数超时 + next/nextOfTimeout 网点」自动推进的,那套东西为真玩家的
 * 按键节奏设计,对假玩家(没有真实按键时序)完全不可控——普通连段 A1/A2 的下一段是 {@code buildFromFrame(5)}
 * 这种「几帧」超时,{@code resolvCurrentComboState} 每被读一次就把它们往前推,于是「挥两下就到底」。
 * 因此这里<b>不依赖网点推进</b>,改由 Numen 自己在若干招式之间轮换,每招用 {@code updateComboSeq} 显式切入
 * (它内部 {@code clickAction} 是完整的第一击),普通连段、挑空、疾走居合都能被明确轮到。
 *
 * <p>「判断斩」这类 SA 的<b>特效</b>在 {@code ComboState.tickAction} 的 {@code TimeLineTickAction.put(0, …)}
 * 里,靠 ticker 逐刻驱动,而假玩家的 ticker 时序对不齐,于是切过去「后退、收刀都出了,唯独中间召唤次元斩
 * 特效没出」。修法:放 SA 后,对「判断斩类 SA」<b>直接调原版 {@link JudgementCut} helper</b>立即生成次元斩实体——
 * 这和 {@code doSlash} 生成实体是同一条已验证可用的链路,不等 ticker。
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

    /** 把假玩家的评价等级顶满 SSS(1800)。 */
    private static void fullRank(LivingEntity entity) {
        entity.getCapability(CapabilityConcentrationRank.RANK_POINT).ifPresent(rank -> {
            rank.setRawRankPoint(1800);
            rank.setLastUpdte(entity.level().getGameTime());
        });
    }

    /**
     * 附近最近的「看得见」的敌对生物,锁定半径与近战警戒半径一致({@code 6} 格)。
     * 找不到返回 null。优先最近、够不着的不锁——锁一个墙后八格外的目标只会徒劳。
     */
    public static LivingEntity nearestTarget(LivingEntity entity) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        double reach = 6.0;
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

    // ==================== 招式轮换 ====================

    /**
     * 步进序号到「普通招式」的映射。循环是四拍,与 {@link SlashBladeMelee.ComboRhythm} 一一对应:
     * <ol>
     *   <li>{@code step%4==0} —— 地面连段 A 起手({@code COMBO_A1},空中则空中下劈);</li>
     *   <li>{@code step%4==1} —— 挑空({@code UPPERSLASH});</li>
     *   <li>{@code step%4==2} —— 疾走居合({@code RAPID_SLASH});</li>
     * </ol>
     * {@code step%4==3} 是 SA 收尾,不走这里。
     */
    private static ResourceLocation moveComboFor(int step, boolean onGround) {
        return switch (step % 4) {
            case 1 -> ComboStateRegistry.UPPERSLASH.getId();
            case 2 -> ComboStateRegistry.RAPID_SLASH.getId();
            default -> onGround
                    ? ComboStateRegistry.COMBO_A1.getId()
                    : ComboStateRegistry.AERIAL_CLEAVE.getId();
        };
    }

    /**
     * 显式切入序号 {@code step} 对应的那一招(直接指定 combo,不依赖网点推进)。
     */
    public static boolean advanceCombo(LivingEntity entity, ItemStack blade, LivingEntity target, int step) {
        if (!isSlashBlade(blade)) {
            return false;
        }
        entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            ResourceLocation loc = moveComboFor(step, entity.onGround());
            if (loc.equals(ComboStateRegistry.NONE.getId())) {
                return false;
            }
            state.updateComboSeq(entity, loc);
            entity.swing(InteractionHand.MAIN_HAND);
            return true;
        }).orElse(false);
    }

    /**
     * 放一次 SA——评价够放超SA,否则普通 SA。放完 doArts+updateComboSeq 后,对「判断斩类 SA」
     * 额外直调 {@link JudgementCut} helper 立即补特效(见类注释)。
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
            fullRank(entity);
            boolean sup = canSuper(blade, entity);
            SlashArts.ArtsType type = sup ? SlashArts.ArtsType.Super : SlashArts.ArtsType.Success;
            ResourceLocation comboLoc = state.getSlashArts().doArts(type, entity);
            ComboState combo = ComboStateRegistry.REGISTRY.get().getValue(comboLoc);
            if (combo == null || Objects.equals(comboLoc, ComboStateRegistry.NONE.getId())) {
                return false;
            }
            state.updateComboSeq(entity, comboLoc);
            entity.swing(InteractionHand.MAIN_HAND);
            // 判断斩类 SA:补立即特效,不等 ticker(ticker 时序对不齐会漏掉「召唤次元斩」那一击)。
            patchJudgementCutArt(entity, blade, sup);
            return true;
        }).orElse(false);
    }

    /** 对「判断斩类」SA 补特效:普通/完美 = 后退+球状次元斩,超 = 48 格锁定次元斩。非判断斩不动作。 */
    private static void patchJudgementCutArt(LivingEntity entity, ItemStack blade, boolean sup) {
        String key = artKey(blade);
        if (key == null || !key.endsWith("judgement_cut")) {
            return;   // 非判断斩类 SA,不补(它们或自带 clickAction,或非本修复范围)
        }
        if (sup) {
            JudgementCut.doJudgementCutSuper(entity);
        } else {
            JudgementCut.doJudgementCut(entity);
        }
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

    /** 放一次剑技:先补锁定+拉满评价,再 doArts → updateComboSeq。判断斩类补 helper 特效。 */
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
        fullRank(entity);
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
