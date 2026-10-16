---
name: slashblade
description: 用拔刀剑(SlashBlade)作战。装了拔刀剑、需要认出主手/背包里的拔刀剑、看它断没断、附什么剑技、拿它挥刀或放剑技时使用。
---

# Skill: 拔刀剑

装了拔刀剑(SlashBlade:Resharped)之后,`slashblade.blade` 组里的函数让你:
认出拔刀剑、看它断没断、附什么剑技、主动挥刀、放剑技(SA)。拔刀剑的伤害、断刀、剑技特效都归拔刀剑自己管,这里只是接一条口子。

## 认得拔刀剑与它的剑技(先看,再打)

```lua
slashblade.blade.held()    -- 主手:slot / item / broken / sa
slashblade.blade.all()     -- 背包里每一把拔刀剑,带格子号、断刀状态、剑技 key
```

- `held = false`(slot = -1)→ 主手不是拔刀剑(可能空手,或拿着别的武器)。
- `broken = true` → 这把刀断了,处于损坏状态,要先修好才能发挥(修理方式问拔刀剑模组,不是 `numen`)。
- `sa` 是这把刀附加的<b>剑技(SA)</b>的 key(如 `slashblade:judgement_cut` 判断斩)。<b>每把刀附一个 SA,
  不同刀 sa 不同</b>——`art()` 放的就是当前主手这把刀的 sa,所以换刀自动换招。

## 用拔刀剑打怪(普通挥刀,照旧走 numen.fight.attack)

**你不需要为了普通拔刀剑挥刀改打法。** 主手是拔刀剑时,`numen.fight.attack` / `numen.fight.clear` 的近战挥击
会自动走拔刀剑自己的攻击管线(`AttackManager.doSlash`),挥出去的就是拔刀剑真正的招式、伤害也归拔刀剑算。
这和车万女仆用拔刀剑的方式一模一样——把拔刀剑当近战武器直接挥,不经过任何技能容器或战斗模式切换。

## 放剑技(SA)

剑技是比普通挥刀更强的一招,由 `slashblade.blade.art` 主动放:

```lua
slashblade.blade.art()            -- 普通释放(默认)
slashblade.blade.art("just")      -- 居合/连击时机判定出的强化版
```

- **先 `slashblade.blade.held()` 看 `sa`** 知道这把刀是什么剑技,再决定放不放、放哪一档。
- **先面向目标**再放(它不替你瞄准,朝你面朝方向出招);先 `numen.move` 走到合适距离开外。
- 返回 `{executed, sa, reason}`:**executed=false 时读 reason 照着做**,别盲目重试。常见原因:断刀(broken)、
  封印(sealed)、或这把刀的 SA 不在支持列表里。
- 剑技要耗资源、有节奏,别无脑连放;普通打怪用 `numen.fight.attack` 的自动挥刀即可,<b>在想要范围更大、
  伤害更高、或特定效果时才放剑技</b>。

### 程序化搓招:你只管喊,不用想怎么搓

**剑技的全部动作、特效、连段已经由服务端程序预置好了,你不用去想"要按哪个键、搓几段"。**
你只需要调 `slashblade.blade.art(...)`,服务端会读当前刀的 SA、照着内置的搓招表一次性放出完整的一招
(含完整的多段特效与伤害)。<b>不要试图自己编排挥刀序列</b>——那是徒劳,而且会打乱节奏。

### 支持的剑技(SA)列表

这些是拔刀剑 1.1.23 内置的全部 SA,`held().sa` 返回它们之一:

| SA(key 短名) | 效果 |
|---|---|
| `judgement_cut` | 判断斩(裂空审判):多目标收刀斩杀,最华丽的连续刀光 |
| `sakura_end` | 樱花终结:两段樱花刀光 |
| `void_slash` | 虚无刀界:大范围爆炸斩 |
| `circle_slash` | 回旋斩:绕身一圈刀光 |
| `drive_vertical` | 垂直冲击波(Drive) |
| `drive_horizontal` | 水平冲击波(Drive) |
| `wave_edge` | 波形冲击:多发冲击波 |

- 判断斩的两档:`art()` 是多目标收刀(完整版),`art("just")` 是单目标居合强化版。
- 其余 SA 只有一档,`art()` 和 `art("just")` 效果相同。

## 动作级控制(切磋、演示、摆架势用)

```lua
slashblade.blade.swing()    -- 普通挥一下主手的拔刀剑(照拔刀剑自己的攻击管线)
```

- 返回 `{executed, reason}`,不瞄准也不走位:先 `numen.move` 走到目标旁、面对目标再挥。
- **真正打起来别用这一条**——照旧用 `numen.fight.attack`,那条路自己走位、自己打,近战挥击自动走拔刀剑管线。

## 和史诗战斗(Epic Fight)的区别

拔刀剑和史诗战斗是**两个不同的模组、两套系统**:

- 拔刀剑 = 它自己的攻击管线(挥刀、断刀、剑技 SA),`slashblade.blade.*` 接的是它;
- 史诗战斗 = 另一套动作系统(切 battle/vanilla 模式、翻滚、格挡),`epicfight.fight.*` 接的是它。

拿拔刀剑打时**不需要**切史诗战斗模式,拿史诗战斗武器时也**不需要**碰 `slashblade.*`。两套并行,互不干扰。

## 别做的事

- 主手没有拔刀剑就调 `slashblade.blade.swing()` / `slashblade.blade.art()`(会明确失败,先看 `held()` 或 `all()`)。
- 拿着断刀还放剑技或指望它满伤害(先修刀)。
- 不面向目标就放剑技(先转身)。
- 无脑连放剑技(它耗骄傲之魂、有节奏,`reason` 会告诉你)。
- 自己编排搓招序列(搓招已由程序内置,照上面的 `art()` 调即可)。
- `slashblade.blade.all()` 结果是空表 = 没装拔刀剑或背包里没有,和主人说,别反复试。
