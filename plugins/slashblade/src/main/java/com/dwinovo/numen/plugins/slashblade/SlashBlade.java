package com.dwinovo.numen.plugins.slashblade;

import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.item.SwordType;
import mods.flammpfeil.slashblade.slasharts.CircleSlash;
import mods.flammpfeil.slashblade.slasharts.Drive;
import mods.flammpfeil.slashblade.slasharts.JudgementCut;
import mods.flammpfeil.slashblade.slasharts.SakuraEnd;
import mods.flammpfeil.slashblade.slasharts.WaveEdge;
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
import java.util.function.BiConsumer;

/**
 * 本插件对接拔刀剑(SlashBlade:Resharped)的那一面——<b>唯一</b>知道拔刀剑类型的地方。
 *
 * <h2>核心:直接调用各 SA 的 helper 生成特效实体,不碰 ComboState 状态机</h2>
 * 平 A / 快速连斩在假玩家身上<b>有刀光、有伤害</b>——那走 {@link AttackManager#doSlash}(生成 {@code EntitySlashEffect})。
 * 这条口子在假玩家身上已验证可用。而「切 ComboState 靠 ticker 逐刻推」这条链在假玩家身上断裂。
 *
 * <h2>终极 SA(超级次元斩)怎么直接放</h2>
 * 照车万女仆「真正的力量」与 mcmod 官方判定:超级 SA = 判断斩的「超级」变式,效果是「以使用者为心、±X/±Y/±Z 各拓 48 格,
 * 范围内所有可锁定目标处召唤次元斩实体 + 2 秒缓慢 XI」。它对应的 combo 是 {@code JUDGEMENT_CUT_END},其 {@code tickAction}
 * 第 0 刻调的就是 {@link JudgementCut#doJudgementCutSuper}——所以<b>这个 helper 就是「终极 SA」的直接入口</b>,直接调它即可,
 * 不经过状态机、不经过蓄力。普通次元斩({@link JudgementCut#doJudgementCut})就是「后退 0.9 格 + 球状次元斩」= 你要的「后撤投光剑」。
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

    /** 能不能放超级 SA:妖 + 锐利边缘 + 非断刀 + 非封印 + 满耐久 + 站地面(照 mcmod 官方判定)。 */
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
     * 各 SA 短名 → 放招动作(照各 SA 的 helper 直调,生成特效实体)。
     * 判断斩:normal=后退+球状次元斩(doJudgementCut),just=完美(doJudgementCutJust,不位移+额外斩击+挑飞)。
     */
    private static final java.util.Map<String, BiConsumer<LivingEntity, Boolean>> ARTS = java.util.Map.ofEntries(
            java.util.Map.entry("judgement_cut", (BiConsumer<LivingEntity, Boolean>) (u, just) -> {
                if (just) {
                    JudgementCut.doJudgementCutJust(u);
                } else {
                    JudgementCut.doJudgementCut(u);
                }
            }),
            java.util.Map.entry("sakura_end", (BiConsumer<LivingEntity, Boolean>) (u, just) -> {
                SakuraEnd.doSlash(u, 22.5F, Vec3.ZERO, false, false, 1.2);
                SakuraEnd.doSlash(u, 180F - 22.5F, Vec3.ZERO, false, true, 1.5);
            }),
            java.util.Map.entry("void_slash", (BiConsumer<LivingEntity, Boolean>) (u, just) ->
                    AttackManager.doVoidSlashAttack(u)),
            java.util.Map.entry("circle_slash", (BiConsumer<LivingEntity, Boolean>) (u, just) -> {
                CircleSlash.doCircleSlashAttack(u, 180);
                CircleSlash.doCircleSlashAttack(u, 90);
                CircleSlash.doCircleSlashAttack(u, 0);
                CircleSlash.doCircleSlashAttack(u, -90);
            }),
            java.util.Map.entry("drive_vertical", (BiConsumer<LivingEntity, Boolean>) (u, just) ->
                    Drive.doSlash(u, -90F, 10, Vec3.ZERO, false, 7, 2f)),
            java.util.Map.entry("drive_horizontal", (BiConsumer<LivingEntity, Boolean>) (u, just) ->
                    Drive.doSlash(u, 0F, 10, Vec3.ZERO, false, 7, 2f)),
            java.util.Map.entry("wave_edge", (BiConsumer<LivingEntity, Boolean>) (u, just) ->
                    WaveEdge.doSlash(u, 90F, 20, Vec3.ZERO, false, 7, 0.2f, 1f, 4)),
            java.util.Map.entry("piercing", (BiConsumer<LivingEntity, Boolean>) (u, just) -> {
                // piercing 没有独立 helper,用前冲 + 连续刀光模拟
                AttackManager.doSlash(u, -30F, Vec3.ZERO, false, false, 1.1f, KnockBacks.toss);
                AttackManager.doSlash(u, 30F, Vec3.ZERO, false, true, 1.1f, KnockBacks.toss);
            }));

    /** 放一次剑技(SA)。{@code type} = normal/just/super/random。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type) {
        return art(entity, blade, type, null);
    }

    /** 放一次剑技,带目标——放之前面向目标。 */
    public static Art art(LivingEntity entity, ItemStack blade, String type, Entity target) {
        if (!isSlashBlade(blade)) {
            return new Art(false, "", "no slash blade in your main hand");
        }
        if (target != null) {
            entity.lookAt(EntityAnchorArgument.Anchor.FEET, target.position());
        }
        String t = type == null ? "random" : type.toLowerCase(Locale.ROOT);
        if ("super".equals(t)) {
            return superArtImpl(entity, blade);
        }
        if ("random".equals(t) && canSuper(blade, entity) && entity.getRandom().nextBoolean()) {
            return superArtImpl(entity, blade);
        }
        boolean just = "just".equals(t)
                || ("random".equals(t) && entity.getRandom().nextBoolean());
        // 断刀/封印先查
        if (blade.getCapability(ItemSlashBlade.BLADESTATE).map(ISlashBladeState::isBroken).orElse(false)) {
            return new Art(false, artKey(blade), "the blade is broken; repair it first");
        }
        if (blade.getCapability(ItemSlashBlade.BLADESTATE).map(ISlashBladeState::isSealed).orElse(false)) {
            return new Art(false, artKey(blade), "the blade is sealed");
        }
        return blade.getCapability(ItemSlashBlade.BLADESTATE).map(state -> {
            ResourceLocation key = state.getSlashArtsKey();
            String shortName = key == null ? "judgement_cut" : key.getPath();
            BiConsumer<LivingEntity, Boolean> action = ARTS.get(shortName);
            if (action == null) {
                // 未知 SA:退回普通次元斩
                JudgementCut.doJudgementCut(entity);
            } else {
                action.accept(entity, just);
            }
            entity.swing(InteractionHand.MAIN_HAND);
            return new Art(true, artKey(blade), "");
        }).orElse(new Art(false, "", "the slash blade has no blade state"));
    }

    /**
     * 放一次终极 SA(超级次元斩):直接调 {@link JudgementCut#doJudgementCutSuper} =「48 格全目标锁定召唤次元斩 + 缓慢 XI」。
     * 需要妖+锐利边缘+满耐久+地面(照官方判定),这不耗一半耐久(那是 ComboState 的 releaseSSA 才做的,这里直调 helper 不改耐久)。
     */
    private static Art superArtImpl(LivingEntity entity, ItemStack blade) {
        if (!canSuper(blade, entity)) {
            return new Art(false, artKey(blade),
                    "the blade needs to be bewitched (enchanted + named), fierce-edged (1000+ kills), "
                            + "unbroken, unsealed, at full durability, and on the ground");
        }
        JudgementCut.doJudgementCutSuper(entity);
        entity.swing(InteractionHand.MAIN_HAND);
        return new Art(true, artKey(blade), "");
    }

    /** 放一次超SA 的别名——= 终极 SA(超级次元斩)。 */
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

    /** 疾走居合:瞬移式前冲(朝面朝方向)+ 连续多段刀光——瞬移能顶过假玩家的寻路物理,冲刺肉眼可见。 */
    private static void releaseRapidSlash(LivingEntity entity) {
        Vec3 look = entity.getLookAngle();
        // 朝面朝方向前冲 3 格(瞬移,不是设速度——假玩家的水平速度会被寻路模块覆盖)
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
}
