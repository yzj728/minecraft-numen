package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.api.combat.Firearms;
import com.dwinovo.numen.api.combat.MeleeWeapons;
import com.dwinovo.numen.api.combat.RangedFirearm;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

/**
 * 打这个目标,近战用哪把、远程用哪把。<b>两条路各挑一把,不排成一张总榜</b>——
 * 它们不可比:弓的攻击力是 0(伤害在箭上),放进同一张按攻击力排的榜里永远垫底,
 * 于是背着弓的她照样会去拿石剑贴脸。够不够得着由 {@link AttackPlan} 判,这里只负责
 * "每条路上最好的那把是哪把"。
 *
 * <p><b>枪械(经 {@link Firearms} 注册)排在远程最前</b>:枪拿起就能打,不用拉弓蓄力;
 * 而且带着枪时近战让位——她是枪手不是剑客,战斗会优先拉开距离开枪,而不是切剑贴脸。
 * 逃跑判据也吃这一口:带枪就是"有武器",不会再被当赤手空拳追着跑。
 *
 * <p><b>枪械之间挑能开火的</b>({@link RangedFirearm#canFire}:弹匣有弹或带着匹配弹药):
 * 两把枪只有一把带着弹时挑那把,不挑到没弹的;所有枪都没弹才轮到别的远程。
 *
 * <p><b>近战按各武器自己的伤害公式排序</b>:原版武器读攻击属性({@link WeaponDamage#against}),
 * 拔刀剑这类伤害走自己一套的经 {@link MeleeWeapons} 报分——它的面板攻击力只是公式一小项,
 * 只按属性排会被铁剑比下去,而它真实更狠。
 */
public final class Loadout {

    /** 一把武器和它所在的格子。{@code slot} 交给 {@link com.dwinovo.numen.pathing.body.Hotbar#hold}。 */
    public record Pick(int slot, ItemStack stack, double score) {}

    private final Pick melee;
    private final Pick ranged;
    private final boolean rangedCharged;

    private Loadout(Pick melee, Pick ranged, boolean rangedCharged) {
        this.melee = melee;
        this.ranged = ranged;
        this.rangedCharged = rangedCharged;
    }

    /** 近战最狠的那把;赤手空拳、或带着枪(枪手不近战)时 null。 */
    public Pick melee() {
        return melee;
    }

    /** 能立刻用的远程武器;没有弓弩/枪、或有弓弩没箭时 null。 */
    public Pick ranged() {
        return ranged;
    }

    public boolean hasMelee() {
        return melee != null;
    }

    public boolean hasRanged() {
        return ranged != null;
    }

    /** 选中的远程武器是把已上弦的弩——它不必再拉一次,可以立刻射。 */
    public boolean rangedCharged() {
        return rangedCharged;
    }

    /**
     * 扫一遍背包。近战按「该武器打这个目标的分值」排序——先问 {@link MeleeWeapons}(拔刀剑等模组武器),
     * 没认领的退回 {@link WeaponDamage#against}(原版攻击属性);远程按
     * "能开火的枪 &gt; 上弦的弩 &gt; 弓 &gt; 未上弦的弩 &gt; 没弹的枪"挑。带着枪时近战为 null:
     * 战斗判据会走远程,拉开距离开枪。
     */
    public static Loadout forTarget(NumenPlayer player, Entity target) {
        Inventory inventory = player.getInventory();
        Pick bestMelee = null;
        Pick chargedCrossbow = null;
        Pick bow = null;
        Pick loadableCrossbow = null;
        Pick bestFirearm = null;
        Pick fallbackFirearm = null;

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (Firearms.isFirearm(stack)) {
                if (fallbackFirearm == null) {
                    fallbackFirearm = new Pick(slot, stack, 0.0);
                }
                RangedFirearm firearm = Firearms.of(stack);
                if (bestFirearm == null && (firearm == null || firearm.canFire(player, stack))) {
                    bestFirearm = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            if (stack.getItem() instanceof CrossbowItem) {
                if (CrossbowItem.isCharged(stack)) {
                    if (chargedCrossbow == null) {
                        chargedCrossbow = new Pick(slot, stack, 0.0);
                    }
                } else if (loadableCrossbow == null && !player.getProjectile(stack).isEmpty()) {
                    loadableCrossbow = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            if (stack.getItem() instanceof BowItem) {
                if (bow == null && !player.getProjectile(stack).isEmpty()) {
                    bow = new Pick(slot, stack, 0.0);
                }
                continue;
            }
            double score = MeleeWeapons.score(player, stack, target);
            if (score <= 0.0) {
                score = WeaponDamage.against(player, target, stack);
            }
            if (score > 0.0 && (bestMelee == null || score > bestMelee.score())) {
                bestMelee = new Pick(slot, stack, score);
            }
        }

        Pick ranged = bestFirearm != null ? bestFirearm
                : chargedCrossbow != null ? chargedCrossbow
                : bow != null ? bow
                : loadableCrossbow != null ? loadableCrossbow
                : fallbackFirearm;
        // 有枪时近战让位:她是枪手,战斗拉开距离开枪,而不是切剑贴脸
        Pick melee = (bestFirearm != null || fallbackFirearm != null) ? null : bestMelee;
        return new Loadout(melee, ranged, ranged != null && ranged == chargedCrossbow);
    }
}
