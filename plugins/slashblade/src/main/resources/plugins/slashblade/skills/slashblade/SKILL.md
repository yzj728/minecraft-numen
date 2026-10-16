---
name: slashblade
description: 用拔刀剑(SlashBlade)作战。装了拔刀剑、需要认出主手/背包里的拔刀剑、看它断没断、拿它挥刀打怪时使用。
---

# Skill: 拔刀剑

装了拔刀剑(SlashBlade:Resharped)之后,`slashblade.blade` 组里的函数让你:
认出拔刀剑、看它断没断、主动挥刀。拔刀剑的伤害、断刀、诛仙技能都归拔刀剑自己管,这里只是接一条口子。

## 认得拔刀剑(先看,再打)

```lua
slashblade.blade.held()    -- 主手:held / item / broken
slashblade.blade.all()     -- 背包里每一把拔刀剑,带格子号与断刀状态
```

- `held = false` → 主手不是拔刀剑(可能空手,或拿着别的武器)。
- `broken = true` → 这把刀断了,处于损坏状态,要先修好才能发挥(修理方式问拔刀剑模组,不是 `numen`)。

## 用拔刀剑打怪(照旧走 numen.fight.attack)

**你不需要为了拔刀剑改打法。** 主手是拔刀剑时,`numen.fight.attack` / `numen.fight.clear` 的近战挥击
会自动走拔刀剑自己的攻击管线(`AttackManager.doSlash`),挥出去的就是拔刀剑真正的招式、伤害也归拔刀剑算。
这和车万女仆用拔刀剑的方式一模一样——把拔刀剑当近战武器直接挥,不经过任何技能容器或战斗模式切换。

所以打怪的完整流程就是:

1. 把拔刀剑拿到主手(`numen.inv`,或让主人直接放主手)。
2. `slashblade.blade.held()` 确认 `held=true`、`broken=false`。
3. 照旧 `numen.fight.attack(...)` / `numen.fight.clear(...)`——你只管走位和点名,拔刀剑招式自动发生。

## 动作级控制(切磋、演示、摆架势用)

```lua
slashblade.blade.swing()    -- 挥一下主手的拔刀剑(照拔刀剑自己的攻击管线)
```

- 返回 `{executed, reason}`:**executed=false 时读 reason 照着做**,别盲目重试。常见原因是没有拔刀剑
  在主手、或还卡在上一个动作里。
- 它不瞄准也不走位:先 `numen.move` 走到目标旁、面对目标,再挥。
- **真正打起来别用这一条**——照旧用 `numen.fight.attack`,那条路自己走位、自己打,近战挥击自动走拔刀剑管线。

## 和史诗战斗(Epic Fight)的区别

拔刀剑和史诗战斗是**两个不同的模组、两套系统**:

- 拔刀剑 = 它自己的攻击管线(挥刀、断刀、诛仙技能),`slashblade.blade.*` 接的是它;
- 史诗战斗 = 另一套动作系统(切 battle/vanilla 模式、翻滚、格挡),`epicfight.fight.*` 接的是它。

拿拔刀剑打时**不需要**切史诗战斗模式,拿史诗战斗武器时也**不需要**碰 `slashblade.*`。两套并行,互不干扰。

## 别做的事

- 主手没有拔刀剑就调 `slashblade.blade.swing()`(会明确失败,先看 `held()` 或 `all()`)。
- 拿着断刀还指望它满伤害(先修刀)。
- 把 `slashblade.blade.swing()` 当打怪的入口(打怪用 `numen.fight.attack`,拔刀剑招式会自动发生)。
- `slashblade.blade.all()` 结果是空表 = 没装拔刀剑或背包里没有,和主人说,别反复试。
