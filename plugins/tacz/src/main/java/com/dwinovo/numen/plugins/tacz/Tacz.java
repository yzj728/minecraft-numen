package com.dwinovo.numen.plugins.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本插件对接 TACZ 的那一面——<b>唯一</b>知道 TACZ 存在的地方,别处(API、技能、入口)都不碰 TACZ 的类型。
 *
 * <h2>为什么可以直接引用它的类</h2>
 * TACZ 代码部分是 GPL-3.0 且开源,类名不混淆(见 {@code TimelessAPI}、{@code IGunOperator})。所以
 * 这里直接编译依赖它,拿真正的枪械对象来驱动同伴,而不是隔着命令行与 NBT 猜。运行时那份由玩家
 * 自己装上的模组提供({@code compileOnly}),本插件不携带它一个字节。
 *
 * <h2>开枪的入口</h2>
 * TACZ 通过 Mixin 给 {@link LivingEntity} 实现了 {@link IGunOperator}(见 TACZ 的
 * {@code LivingEntityMixin}),同伴是服务端假玩家({@code ServerPlayer} 是 {@code LivingEntity} 的子类),
 * 所以 {@code IGunOperator.fromLivingEntity(companion)} 直接可用。开枪是服务端逻辑,不依赖客户端网络包:
 * {@link IGunOperator#draw} 设置当前枪、{@link IGunOperator#shoot} 朝给定俯仰/偏航开一枪。
 *
 * <h2>为什么用 {@code shoot(pitch, yaw)} 而不是带时间戳的那个</h2>
 * 带时间戳的重载要过一个 {@code SERVER_SHOOT_NETWORK_V} 的网络时钟校验,那个是给真客户端抗延迟用的;
 * 不带时间戳的重载在 TACZ 的 mixin 里自己填 {@code now - baseTimestamp},正好落在校验窗口内,同伴(没有
 * 客户端)才开得出这一枪。
 *
 * <h2>枪械是状态机,开枪前要把状态摆对</h2>
 * TACZ 的 {@code shoot} 对枪的状态有前置检查,不满足时一枪不开、只回一个结果码:换弹中({@code IS_RELOADING})、
 * 拉栓中({@code IS_BOLTING})、疾跑中({@code IS_SPRINTING})、手动枪膛内无弹({@code NEED_BOLT})、冷却中
 * ({@code COOL_DOWN})。所以本插件除了扣扳机,还要把手动枪的拉栓({@link #bolt})暴露出去,并把枪的状态
 * ({@link #held})原样报给模型——它看结果码自己把状态摆对,本类不替它做重试。
 */
public final class Tacz {

    private Tacz() {}

    /** TACZ 装了没有。没装的话本插件全程安静,不报错。 */
    public static boolean present() {
        try {
            TimelessAPI.getAllCommonGunIndex();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** TACZ 认得的全部枪械 id,按名排序。 */
    public static List<String> gunIds() {
        Set<Map.Entry<ResourceLocation, CommonGunIndex>> all = TimelessAPI.getAllCommonGunIndex();
        List<String> out = new ArrayList<>(all.size());
        for (Map.Entry<ResourceLocation, CommonGunIndex> e : all) {
            out.add(e.getKey().toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    /** 一把枪此刻的弹药数;不是枪返回 -1。 */
    public static int ammoCount(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        return gun == null ? -1 : gun.getCurrentAmmoCount(stack);
    }

    /** 主手一把枪此刻的状态。 */
    public record Held(boolean bulletInBarrel, boolean reloading, boolean bolting, boolean sprinting,
                       long coolDownMs) {}

    /** 读主手那把枪的服务端状态:膛内子弹、换弹中、拉栓中、疾跑中、射击冷却还剩多少毫秒。不是枪返回 null。 */
    public static Held held(LivingEntity shooter, ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return null;
        }
        IGunOperator op = IGunOperator.fromLivingEntity(shooter);
        ShooterDataHolder data = op.getDataHolder();
        return new Held(gun.hasBulletInBarrel(stack), data.reloadStateType.isReloading(), data.isBolting,
                data.sprintTimeS > 0, op.getSynShootCoolDown());
    }

    /** 一个玩家的背包里有没有某把枪(id 匹配),有的话在第几格。返回 -1 表示没有。 */
    public static int findGunSlot(Player player, String gunId) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun != null && gun.getGunId(stack).toString().equals(gunId)) {
                return i;
            }
        }
        return -1;
    }

    /** 主手是不是某把枪。 */
    public static boolean mainHandIs(Player player, String gunId) {
        IGun gun = IGun.getIGunOrNull(player.getMainHandItem());
        return gun != null && gun.getGunId(player.getMainHandItem()).toString().equals(gunId);
    }

    /**
     * 把背包里那把枪拿到主手,并初始化 TACZ 的射击状态。调用方保证能从背包里找到这把枪。
     * 找得到才来:找不到会静默不动作,所以调用方要先问 {@link #findGunSlot}。
     */
    public static void equip(Player player, String gunId) {
        if (mainHandIs(player, gunId)) {
            IGunOperator.fromLivingEntity(player).initialData();
            return;
        }
        Inventory inv = player.getInventory();
        int slot = findGunSlot(player, gunId);
        if (slot < 0) {
            return;
        }
        ItemStack gun = inv.getItem(slot);
        // 把枪换到主手:背包格与主手互换,原主手物品落回那一格,不掉东西
        ItemStack hand = player.getItemInHand(InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, gun);
        inv.setItem(slot, hand);
        IGunOperator.fromLivingEntity(player).initialData();
    }

    /** 拉栓:手动枪(开火回执是 need_bolt)把下一发顶上膛。自动枪不需要,拉了也不动。 */
    public static void bolt(LivingEntity shooter) {
        IGunOperator.fromLivingEntity(shooter).bolt();
    }

    /** 朝目标的视线方向开一枪:算指向它的俯仰/偏航,交给 TACZ 的 shoot。 */
    public static ShootResult shoot(LivingEntity shooter, LivingEntity target) {
        IGunOperator op = IGunOperator.fromLivingEntity(shooter);
        return op.shoot(() -> pitch(shooter, target), () -> yaw(shooter, target));
    }

    public static void reload(LivingEntity shooter) {
        IGunOperator.fromLivingEntity(shooter).reload();
    }

    public static void fireSelect(LivingEntity shooter) {
        IGunOperator.fromLivingEntity(shooter).fireSelect();
    }

    public static void aim(LivingEntity shooter, boolean aiming) {
        IGunOperator.fromLivingEntity(shooter).aim(aiming);
    }

    /** 俯仰角(xRot):从眼睛朝目标眼睛的视线。原版 {@code Vec3.directionFromRotation} 的反解。 */
    private static float pitch(LivingEntity shooter, LivingEntity target) {
        net.minecraft.world.phys.Vec3 d = view(shooter, target);
        return (float) Math.toDegrees(Math.asin(-d.y));
    }

    /** 偏航角(yRot):同上。 */
    private static float yaw(LivingEntity shooter, LivingEntity target) {
        net.minecraft.world.phys.Vec3 d = view(shooter, target);
        return (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    private static net.minecraft.world.phys.Vec3 view(LivingEntity shooter, LivingEntity target) {
        return target.getEyePosition().subtract(shooter.getEyePosition()).normalize();
    }
}
