---
name: slashblade
description: 用拔刀剑(SlashBlade)作战。装了拔刀剑、需要认出主手/背包里的拔刀剑、看它断没断、附什么剑技、拿它挥刀或放剑技时使用。
---

# Skill: 拔刀剑

装了拔刀剑(SlashBlade:Resharped)之后,`slashblade.blade` 组里的函数让你:
认出拔刀剑、看它断没断、附什么剑技、主动挥刀、放剑技(SA)、放超剑技(超SA)、打一整套连击、读刀的特殊效果(SE)。拔刀剑的伤害、断刀、剑技特效都归拔刀剑自己管,这里只是接一条口子。

## 认得拔刀剑与它的剑技(先看,再打)

```lua
slashblade.blade.held()    -- 主手:slot / item / broken / sa
slashblade.blade.all()     -- 背包里每一把拔刀剑,带格子号、断刀状态、剑技 key
slashblade.blade.se()      -- 主手这把刀附的特殊效果(SE)列表
```

- `held = false`(slot = -1)→ 主手不是拔刀剑(可能空手,或拿着别的武器)。
- `broken = true` → 这把刀断了,处于损坏状态,要先修好才能发挥(修理方式问拔刀剑模组,不是 `numen`)。
- `sa` 是这把刀附加的<b>剑技(SA)</b>的 key。`se` 是<b>特殊效果(SE)</b>,是被动效果、不是主动放的招。

## 用拔刀剑打怪(普通挥刀,照旧走 numen.fight.attack)

**你不需要为了普通拔刀剑挥刀改打法。** 主手是拔刀剑时,`numen.fight.attack` / `numen.fight.clear` 的近战挥击
会自动走拔刀剑自己的攻击管线,而且<b>带冷却自动搓随机档位的剑技</b>(能放超SA 就有一半概率超SA,否则普通/完美
各半),不用你手动喊。

武器选择也不用你管——拔刀剑的近战评分已接进 numen 的武器排序,它会优先把拔刀剑切到主手,不会"有铁剑就不认拔刀剑"。

## 放剑技(SA)——预制,不用蓄力

剑技由 `slashblade.blade.art` 主动放:

```lua
slashblade.blade.art()              -- 随机档(默认):服务端挑能放的,能超SA 就一半概率超SA
slashblade.blade.art("normal")      -- 普通档
slashblade.blade.art("just")        -- 完美档(居合强化)
slashblade.blade.art("super")       -- 超SA(条件见下)
```

- **完全预制、不用蓄力**:你不需要"长按右键掐完美时机"。`"just"` 进来就是完美版,`"super"` 只要刀够格就
  必出,`"random"` 由服务端随机挑。那一整套多段刀光、位移、收刀会在接下来几刻由服务端自动播完。
- **先面向目标**再放(它不替你瞄准);先 `numen.move` 走到合适距离。
- 返回 `{executed, sa, reason}`:`executed=false` 读 reason,别盲目重试。常见原因:断刀(broken)、封印(sealed)、
  未附魔(白板刀没有 SA)。
- 剑技耗资源、有节奏,别无脑连放;普通打怪交给 `numen.fight.attack` 的自动搓招即可。

### 打一整套连击

```lua
slashblade.blade.combo()    -- 随机起手一条完整连段(地面普攻连段或疾走居合,空中空中连段),整条自动推完
```

- 你喊一句,服务端随机挑一个起手连段,之后整条连段树(多段刀光、位移、收刀)逐刻自动衔接、自动收尾。
- **别每刻重复调 `combo()`**——一次起手,后面是自动的;反复调会打断节奏。
- 先面向目标。

## 放超剑技(超SA)

```lua
slashblade.blade.super_art()
```

- 等价于 `slashblade.blade.art("super")`。
- **条件**:刀同时是<b>妖刀</b>(附魔 + 自定义名)和<b>锐利边缘</b>(杀敌数 ≥ 1000)、非断刀、非封印、
  <b>满耐久</b>、站地面。失败时 reason 会告诉你缺哪条。

## 动作级控制(切磋、演示用)

```lua
slashblade.blade.swing()    -- 普通挥一下主手的拔刀剑
```

- 返回 `{executed, reason}`,不瞄准也不走位。**真正打起来别用它**,照旧 `numen.fight.attack`。

## 和史诗战斗(Epic Fight)的区别

拔刀剑和史诗战斗是**两个不同的模组、两套系统**,`slashblade.blade.*` 和 `epicfight.fight.*` 各接各的,互不干扰。

## 别做的事

- 主手没有拔刀剑就调 `swing` / `art` / `combo`(先看 `held()` / `all()`)。
- 断刀还放剑技(先修刀)。
- 未附魔白板刀放剑技(它没有 SA)。
- 不面向目标就放剑技(先转身)。
- 每刻重复调 `art` / `combo`(一次放完,后面自动播;重复调打乱节奏、耗骄傲之魂)。
- 自己编排挥刀序列(招式已预制,`art` / `combo` 一次调用打完整套)。
- 把 SE 当主动招去"放"(SE 被动,只能读)。
- `slashblade.blade.all()` 空表 = 没装拔刀剑或背包里没有,和主人说。
