package com.dwinovo.numen.plugins.tacz;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.GunTabType;
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
import java.util.Locale;
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
 * 所以 {@code IGunOperator.fromLivingEntity(companion)} 直接可用。
 *
 * <h2>打法的出处:车万女仆验证过的枪械逻辑</h2>
 * 车万女仆(TouhouLittleMaid)官方内置了 TACZ 兼容,它的 {@code TacInnerCompat.performGunAttack}
 * 是<b>在真实游戏里验证过能打中</b>的完整状态机:瞄准角公式、狙击枪开镜、拉栓、换弹、
 * NOT_DRAW 时切枪。本插件的瞄准与准备流程照它逐条对齐——女仆怎么做,同伴就怎么做,
 * 只有"目标"和"身体"换成同伴自己。
 *
 * <h2>为什么用 {@code shoot(pitch, yaw)} 而不是带时间戳的那个</h2>
 * 带时间戳的重载要过一个 {@code SERVER_SHOOT_NETWORK_V} 的网络时钟校验,那个是给真客户端抗延迟用的;
 * 不带时间戳的重载在 TACZ 的 mixin 里自己填 {@code now - baseTimestamp},正好落在校验窗口内,同伴(没有
 * 客户端)才开得出这一枪。女仆走的是同一条无时间戳路径。
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

    /** 切枪:把主手物品登记成当前操作的枪(女仆的 NOT_DRAW 处理)。 */
    public static void draw(LivingEntity shooter) {
        IGunOperator.fromLivingEntity(shooter).draw(shooter::getMainHandItem);
    }

    /** 拉栓:手动枪(开火回执是 need_bolt)把下一发顶上膛。自动枪不需要,拉了也不动。 */
    public static void bolt(LivingEntity shooter) {
        IGunOperator.fromLivingEntity(shooter).bolt();
    }

    /** 朝目标的视线方向开一枪。瞄准角照车万女仆验证过的公式,一字不改。 */
    public static ShootResult shoot(LivingEntity shooter, LivingEntity target) {
        IGunOperator op = IGunOperator.fromLivingEntity(shooter);
        // 车万女仆 TacInnerCompat.performGunAttack 的瞄准公式:目标中心对射手脚底
        double x = target.getX() - shooter.getX();
        double y = target.getEyeY() - shooter.getEyeY();
        double z = target.getZ() - shooter.getZ();
        float yaw = (float) -Math.toDegrees(Math.atan2(x, z));
        float pitch = (float) -Math.toDegrees(Math.atan2(y, Math.sqrt(x * x + z * z)));
        return op.shoot(() -> pitch, () -> yaw);
    }

    /** 朝面朝方向开一枪(无目标:测试开火、盲射用),子弹沿实体当前视线飞。 */
    public static ShootResult fire(LivingEntity shooter) {
        IGunOperator op = IGunOperator.fromLivingEntity(shooter);
        return op.shoot(shooter::getXRot, shooter::getYRot);
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

    /** 她此刻开没开镜。 */
    public static boolean isAiming(LivingEntity shooter) {
        return IGunOperator.fromLivingEntity(shooter).getSynIsAiming();
    }

    /** 这把枪是不是狙击枪(按 TACZ 的枪械分类)。女仆对狙击枪必开镜再打。 */
    public static boolean isSniper(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return false;
        }
        return TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> GunTabType.SNIPER.name().toLowerCase(Locale.ENGLISH).equals(index.getType()))
                .orElse(false);
    }

    /** 这把枪的瞄准耗时(秒):开镜进度走满要这么久。查不到按默认 0.2。 */
    public static float aimTimeSeconds(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return 0.2f;
        }
        return TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> index.getGunData().getAimTime()).orElse(0.2f);
    }

    /** 这把枪的拉栓耗时(秒)。 */
    public static float boltTimeSeconds(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return 0f;
        }
        return TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
                .map(index -> index.getGunData().getBoltActionTime()).orElse(0f);
    }

    /** 秒折成刻,外加两刻余量——女仆那套"等 aimTime/drawTime/boltTime 再打"的同款算法。 */
    public static long waitTicks(float seconds) {
        return Math.round(Math.max(0f, seconds) * 20) + 2;
    }
}
