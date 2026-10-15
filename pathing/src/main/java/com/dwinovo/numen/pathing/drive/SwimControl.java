package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 游:往上按住跳(原版在水里按住跳就往上浮),往下按住潜行(原版客户端潜行时往下沉);平挪时上下各有一条线,脚沉到这一格的下半截
 * 就按跳托住,浮到上半截就按潜行压回去,别让浮力把身体托到上面一格、漂离路线。水平方向朝落点那一列游。
 * 水里不疾跑:疾跑会变成游泳姿势,身子只剩 0.6 高。
 *
 * <h2>水平方向不走 {@link Steering}</h2>
 * {@code Steering.stop/pass} 按地面摩擦与空气加速度推算,在水里这套物理不成立(水里有浮力、有额外的液体阻力),而且它原先只在
 * {@code onGround} 时转身——水里 {@code onGround} 恒为 false,身体只朝旧方向游,原地打转。这里改为:先把身体转到目标列
 * ({@link Aim#faceToward}),再按前进朝它游,接近了松开让水阻力自然停住。到没到那一列不靠这里精确停,由段状态机的认步
 * ({@code Driver.track})看身体落进了哪个节点兜底。
 */
final class SwimControl extends Control {

    SwimControl(Rig rig, Maneuver m, Maneuver next) {
        super(rig, m, next);
    }

    @Override
    Beat tick() {
        Beat edits = editsInPlace(allEdits());
        if (edits != null) {
            return edits;
        }
        keys().release(Key.SPRINT);
        int dy = m.to().getY() - m.from().getY();
        double feet = rig.entity.getY();
        // 平挪(dy==0)时上下两条线:沉到这一格的下半截按跳托住,浮到上半截按潜行压回。原来只托下不压下,
        // 浮力把身体越托越高、漂到上面一格,轨迹就偏了——原版水里不按键身体会慢慢往上浮。
        keys().set(Key.JUMP, dy > 0 || dy == 0 && feet < m.to().getY() + FLOAT);
        keys().set(Key.SNEAK, dy < 0 || dy == 0 && feet > m.to().getY() + 1 - FLOAT);
        Vec3 c = center(m.to());
        // 水中转向目标列
        Aim.faceToward(rig.entity, c.x, c.z);
        double dx = c.x - rig.entity.getX();
        double dz = c.z - rig.entity.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        // 朝目标游:还在一格开外就按前进,贴近目标列了松开,让水阻力停住;认步兜底
        keys().release(Key.BACK);
        keys().set(Key.FORWARD, dist > 0.2);
        return Beat.IDLE;
    }
}
