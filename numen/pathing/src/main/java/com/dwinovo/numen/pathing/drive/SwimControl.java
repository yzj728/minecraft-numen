package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;

import net.minecraft.world.phys.Vec3;

/**
 * 游:往上按住跳(原版在水里按住跳就往上浮),往下按住潜行(原版客户端潜行时往下沉);平挪时上下各有一条线,脚沉到这一格的
 * 下半截就按跳托住,浮到上半截就按潜行压回去,别让浮力把身体托到上面一格、漂离路线。水平方向朝落点那一列游。
 * 水里不疾跑:疾跑会变成游泳姿势,身子只剩 0.6 高。
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
        if (flows()) {
            Steering.pass(rig, c.x, c.z);
        } else {
            Steering.stop(rig, c.x, c.z, m.landing().feetY());
        }
        return Beat.IDLE;
    }
}
