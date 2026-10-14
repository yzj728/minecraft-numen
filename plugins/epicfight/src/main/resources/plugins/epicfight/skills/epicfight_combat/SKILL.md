---
name: epicfight_combat
description: 用史诗战斗(Epic Fight)作战。装了史诗战斗、需要认武器、切战斗模式、挥剑连击、翻滚躲闪、举武器格挡时使用。
---

# Skill: epicfight_combat

装了史诗战斗(Epic Fight)之后,`epicfight.fight` 组里的函数让你:
认出它定义的武器、在普通模式与战斗模式之间切换、用它的动作系统挥剑/翻滚/格挡。

## 版本适配(MC 1.20.1 的 20.14.x)

- 插件编译对着 Epic Fight 20.14.17(1.20.1 线的最后一个版本),但运行期用的是玩家自己装的那一份;
  它只走 Epic Fight 20.9+ 就有的公开执行口(`requestCasting` 一族),所以 20.14.8–20.14.17 都能跑,
  不必逐个小版本重新编译。
- 这个 MC 版本 Epic Fight 只发 Forge 版,所以本联动只在 Forge 上存在;Fabric/NeoForge 上没有它。

## 认得武器(先看,再打)

史诗战斗给武器定义了两样东西:**类别**(category)与**握法**(style)。

```lua
epicfight.fight.weapon()    -- 主手物品:recognized / category / style
epicfight.fight.weapons()   -- 背包里每一件史诗战斗认得的武器,带格子号与类别
epicfight.fight.status()    -- 装了没有、当前模式、体力、是否在格挡、主手武器
```

- `recognized = false` → 这件东西不是史诗战斗武器(徒手也是 false:徒手是拳头,不是武器)。
- 常见类别:`SWORD` 剑、`AXE` 斧、`SPEAR` 长矛、`UCHIGATANA` 太刀、`TACHI` 打刀、`GREATSWORD` 大剑、
  `LONGSWORD` 长剑、`DAGGER` 匕首、`BOW` 弓、`CROSSBOW` 弩、`SHIELD` 盾。
- 握法:`ONE_HAND` 单手、`TWO_HAND` 双手——决定它出什么动作。

## 切模式(普通模式 ↔ 史诗战斗模式)

```lua
epicfight.fight.mode("battle")    -- 史诗战斗模式:武器有动作、能翻滚格挡
epicfight.fight.mode("vanilla")   -- 普通模式:原版挥击,和没装史诗战斗时一样
```

**什么时候用哪个:**
- 用史诗战斗武器近战、或眼前的怪会用史诗战斗招式 → `battle`。
- 用 TACZ 枪打 → 保持 `vanilla`(枪走自己的开火路径,不归史诗战斗管,两套系统别叠在一起)。
- 模式只影响你自己,主人和其他生物的模式不受影响。当前是什么看 `status().mode`。

## 动作(都在 battle 模式下)

```lua
epicfight.fight.attack()                -- 挥一下(按你拿的武器出招);连续调用就是连击
epicfight.fight.dodge("forward")        -- 向前翻滚
epicfight.fight.dodge("backward")       -- 向后翻滚(朝眼前的敌人方向退)
epicfight.fight.guard(true)             -- 举起武器格挡(挡正面近战;要拿能格挡的武器)
epicfight.fight.guard(false)            -- 放下格挡
```

- 每个动作返回 `{executed, reason}`:**executed=false 时读 reason 照着做**,别盲目重试。
  常见原因是"没体力(`status().stamina`)"或"还在上一个动作里,等一下再试"。
- 翻滚耗体力,体力低了别连滚;格挡打斗间隙要放下。

## 自动格挡、翻滚与挥砍动画(不用你每刻去叫)

本体自带一个战斗本能,你在 `battle` 模式下时——

- **有怪逼到近战距离,自动举起武器格挡**;它退开(或你切回 vanilla)自动放下。挡的是正面近战,
  箭、爆炸、魔法挡不住,那是史诗战斗自己的规则。
- **挨了一下,自动朝伤害来源方向向后翻滚拉开距离**,两次翻滚之间有冷却(不会把体力连滚抽干)。
- **近战挥击自动走史诗战斗的 `BASIC_ATTACK` 技能**——`numen.fight.attack` 或自卫时,挥出去的是
  史诗战斗的连击动画,伤害也走史诗战斗管线,不再是原版那一下干挥。

所以你不用每个 tick 去调 `guard`/`dodge`/`attack`——它们在你打起来时会自己发生。你只需:
切到 battle、点名打谁(照旧 `numen.fight.attack`),想要<b>精确控制</b>某一招时才手动调 `epicfight.fight.*`。

## 和 numen.fight.attack 的分工

- **有人点名打谁、或自卫时**:照旧用 `numen.fight.attack` / `numen.fight.clear`——
  战斗系统自己走位、自己打。战斗模式下的近战伤害走史诗战斗的伤害管线,回执照旧可靠。
- **动作级控制**(切磋、演示、躲技能、摆架势):用 `epicfight.fight.*` 一组,一次一个动作。

## 别做的事

- vanilla 模式下调用 attack/dodge/guard——会明确失败并提示你先切 battle。
- 没体力还连滚连招——先看 `epicfight.fight.status().stamina`。
- 拿着 TACZ 枪切 battle 模式指望枪变厉害——枪不归史诗战斗管,开枪用 `tacz.gun.shoot`。
- `status().installed = false` 就是没装史诗战斗,和主人说,别反复试。
