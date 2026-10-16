---
name: slashblade
description: 用拔刀剑(SlashBlade)作战。装了拔刀剑、需要认出主手/背包里的拔刀剑、看它断没断、附什么剑技、拿它挥刀、放剑技或直接放招式时使用。
---

# Skill: 拔刀剑

装了拔刀剑(SlashBlade:Resharped)之后,`slashblade.blade` 组里的函数让你:
认出拔刀剑、看它断没断、附什么剑技、主动挥刀、放剑技(SA)、直接放招式(疾走居合/挑空/空中技)、打一整套连击、读刀的特殊效果(SE)。拔刀剑的伤害、断刀、剑技特效都归拔刀剑自己管,这里只是接一条口子。

## 认得拔刀剑与它的剑技(先看,再打)

```lua
slashblade.blade.held()    -- 主手:slot / item / broken / sa
slashblade.blade.all()     -- 背包里每一把拔刀剑,带格子号、断刀状态、剑技 key
slashblade.blade.se()      -- 主手这把刀附的特殊效果(SE)列表
```

- `held = false`(slot = -1)→ 主手不是拔刀剑(可能空手,或拿着别的武器)。
- `broken = true` → 这把刀断了,要先修好(修理方式问拔刀剑模组,不是 numen)。
- `sa` 是这把刀附加的<b>剑技(SA)</b>的 key(如 `slashblade:judgement_cut` 判断斩)。每把刀附一个 SA。
- `se` 是这把刀附的<b>特殊效果(SE)</b>。<b>SE 是被动效果,不是主动放的招</b>——它随刀生效(玩家经验达到触发等级就自动触发,或在你放其他招时顺带触发),不能「点名放一个 SE」。1.1.23 内置只有 `slashblade:wither_edge`(凋零之刃),空表说明没附。

## 用拔刀剑打怪(普通战斗,照旧走 numen.fight.attack)

**你不需要为了普通拔刀剑挥刀改打法。** 主手是拔刀剑时,`numen.fight.attack` / `numen.fight.clear` 的近战挥击会自动走拔刀剑管线,而且会带冷却自动放随机档位的剑技。武器选择也不用你管——拔刀剑的近战评分已接进排序,会优先切到主手。

## 放剑技(SA)——预制,不用蓄力

```lua
slashblade.blade.art()              -- 随机档(默认)
slashblade.blade.art("normal")      -- 普通档
slashblade.blade.art("just")        -- 完美档(居合强化)
slashblade.blade.art("super")       -- 超SA(条件见下)
```

- **完全预制、不用蓄力**:"just" 进来就是完美版,"super" 只要刀够格就必出,"random" 由服务端随机挑。多段刀光自动播完。
- **先面向目标**再放(它不替你瞄准);先 numen.move 走到合适距离。
- 返回 `{executed, sa, reason}`:失败读 reason。常见:断刀、封印、未附魔。

## 直接放招式——完全跳过搓招

这是<b>最快出招</b>的路:直接指定一个招式,服务端切到那一招、当场放第一击、后续多段自动播完,<b>没有蓄力、没有连段输入</b>。

```lua
slashblade.blade.release("rapid_slash")       -- 疾走居合:前冲 + 连续刀光(贴脸用)
slashblade.blade.release("upperslash")        -- 挑空
slashblade.blade.release("upperslash_jump")   -- 跃升斩:升空 + 下劈
slashblade.blade.release("aerial_cleave")     -- 空中下劈(你在空中时用)
slashblade.blade.release("rising_star")       -- 晨星:上挑连击
```

- **场景**:目标就在跟前、要立刻打中,用 `rapid_slash` 或 `upperslash`;
  你在空中或要打天上的/隔开的,用 `aerial_cleave` / `upperslash_jump`。
- 返回 `{executed, reason}`,失败读 reason(招式名拼错会列出来)。
- **先面向目标**再放。

### 打一整套连击

```lua
slashblade.blade.combo()    -- 随机起手一条完整连段,整条自动推完
```

- 一次起手,后面自动衔接,**别每刻重复调**。

## 放超剑技(超SA)

```lua
slashblade.blade.super_art()
```

- 等价于 `art("super")`。**条件**:妖刀(附魔+自定义名)+ 锐利边缘(杀敌≥1000)、非断刀、非封印、满耐久、站地面。失败 reason 会告诉你缺哪条。**超SA 消耗刀 50% 耐久**,别乱放。

## 动作级控制(切磋、演示用)

```lua
slashblade.blade.swing()    -- 普通挥一下
```

- 真正打起来别用,照旧 numen.fight.attack。

## 和史诗战斗(Epic Fight)的区别

拔刀剑和史诗战斗是**两个不同的模组、两套系统**,`slashblade.blade.*` 和 `epicfight.fight.*` 各接各的,互不干扰。

## 别做的事

- 主手没有拔刀剑就调 swing / art / release / combo(先看 held / all)。
- 断刀还放剑技(先修刀)。
- 未附魔白板刀放剑技(它没有 SA)。
- 不面向目标就放(先转身)。
- 每刻重复调 art / release / combo(一次放完后面自动播;重复调打乱节奏、耗骄傲之魂)。
- 把 SE 当主动招去「放」(SE 是被动,只能读)。
- 想「放一个 SE」——做不到,SE 是被动;你只能选带某 SE 的刀(读 se)。
- `slashblade.blade.all()` 空表 = 没装拔刀剑或背包里没有,和主人说。
