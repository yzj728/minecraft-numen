---
name: tacz_gun
description: 用 TACZ 枪械作战。需要开枪、射击、清怪、自卫、或者主人让你"用枪打"时使用。前提是你背包里已经有 TACZ 枪和弹药。
---

# Skill: tacz_gun

装了 TACZ 之后,`tacz.gun` 组里的函数能让你拿枪、开枪、拉栓、换弹、切开火模式、开镜。
每个怎么写,用 `numen.api.help("tacz.gun.shoot")` 查;这里只讲什么时候用、怎么连起来用。

## 开枪前先看手上有什么

`tacz.gun.options()` 一次给你:装了哪些枪(枪 id 清单)、你主手现在拿的是哪把、弹匣里几发、
膛内有没有子弹、是否在换弹/拉栓/疾跑、射击冷却还剩多少毫秒。

**枪是远程武器,但它不会替你看距离和视野。** 打得中打不中,取决于你站的位置和看不看得见目标;
开枪前先用 `numen.scan.sight(目标)` 确认看得见、没有墙挡着,离太远就用 `numen.move.to` 走近一点。

## 拿枪

你背包里要有那把枪(主人给你的,或者 `numen.inv` 里查得到)。有就拿上:

```lua
tacz.gun.equip("tacz:ak47")
```

枪 id 从 `tacz.gun.options().guns` 里照原样抄,别凭印象猜。背包里没有会明确失败——那是真的没有,去跟主人要,别反复试同一把。

## 识别敌人

```lua
local foe = numen.scan.entities("hostile", {radius = 24})[1]
```

每个结果是实体,带 `id`、`type`、`pos`、`distance`、`hp`。挑最近的一只,记得它的 `id`。

## 走位 + 开火循环

枪是远程,站远一点也能打,但要看得见、要站定(疾跑中开不了枪):

```lua
numen.move.to(foe.pos, {arrive = "near", range = 10})   -- 走到目标 10 格附近,到地方身体自己会站定
tacz.gun.equip("tacz:ak47")                             -- 拿上枪
local ok = numen.scan.sight(foe)                        -- 确认看得见
if ok.visible then
  local shot = tacz.gun.shoot(foe)                      -- 扣一次扳机
end
```

`tacz.gun.shoot(目标)` 是一次扣扳机,不吃距离参数、也不走路。它返回的 `result` 告诉你这一枪怎么回事,
**照码办,别硬重试**:

| result | 意思 | 下一步 |
|---|---|---|
| `success` | 打出去了 | 看敌人掉没掉血,再打下一发(自动枪) |
| `need_bolt` | 手动枪膛里没子弹 | `tacz.gun.bolt()` 拉栓,再打 |
| `no_ammo` | 弹匣空了 | `tacz.gun.reload()` 换弹,等 `options().reloading` 变 false 再打 |
| `is_reloading` | 换弹还没完 | 等一会儿(用 `numen.task.timer` 提醒自己),查 `options()` 再打 |
| `is_bolting` | 拉栓还没完 | 同上,等 `options().bolting` 变 false |
| `is_sprinting` | 你在疾跑 | 站定再打 |
| `cool_down` | 射击冷却中 | 查 `options().cool_down_ms`,等那么久再打 |
| `not_gun` / `not_draw` | 手上不是枪 | `tacz.gun.equip` 重新拿枪 |

**手动枪(栓动/部分狙)打一枪要拉一次栓**:`shoot` → `success` 后膛空了,下一枪会 `need_bolt`,
先 `tacz.gun.bolt()` 再 `shoot`。自动枪不用管膛的事,连续扣就行。

## 换弹 / 开火模式 / 开镜

```lua
tacz.gun.reload()        -- 换弹:只"开始",换没换完看 options().reloading
tacz.gun.fire_select()   -- 单发/全自动/连发之间轮换
tacz.gun.aim(true)       -- 开镜(减散布,可选)
tacz.gun.aim(false)      -- 收镜
```

**注意:客户端设置里的"自动换弹"对你无效**——你没有客户端,换弹永远要你自己调 `tacz.gun.reload()`。

## 避免误伤(最重要的一条)

开枪是伤人,**对谁开枪由权限层裁决**:你朝主人的宠物、命名过的动物、或者村民开枪,权限层会拦下来或先问主人。
所以你不要自己判断"这枪能不能打",正常瞄准目标开枪就行,不该打的自然打不出去。

但你自己也要守两条:
- 开枪前扫一眼 `numen.scan.entities("player", {radius = 16})`,别把主人卷进你和怪之间的弹道。
- 别朝会爆炸的东西(苦力怕)贴脸打,站爆炸波及范围之外再打。

## 别做的事

- 手上没枪、背包里没枪就假装开枪
- 没子弹了还一遍遍扣扳机(看 shoot 返回的 no_ammo)
- 对着墙后的敌人一直射(先 numen.scan.sight + numen.move.to 换位置)
- 不看 result 就连续重试同一次 shoot——结果码就是告诉你怎么改的
- 把开枪当成唯一手段:近处、弹药不足时,用 numen.fight.attack 近战更省
