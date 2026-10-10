---
name: tacz_gun
description: 用 TACZ 枪械作战。需要开枪、射击、清怪、自卫、或者主人让你"用枪打"时使用。前提是你背包里已经有 TACZ 枪和弹药。
---

# Skill: tacz_gun

装了 TACZ 之后,`tacz.gun` 组里的函数能让你拿枪、开枪、换弹、切开火模式、开镜。
每个怎么写,用 `numen.api.help("tacz.gun.shoot")` 查;这里只讲什么时候用、怎么连起来用。

## 开枪前先看手上有什么

`tacz.gun.options()` 一次给你三样:这个服务器装了哪些枪(枪 id 清单)、你主手现在拿的是哪把、
它里面还剩几发。

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

## 走位 + 瞄准 + 开枪

枪是远程,站远一点也能打,但要看得见:

```lua
numen.move.to(foe.pos, {arrive = "near", range = 10})   -- 走到目标 10 格附近
tacz.gun.equip("tacz:ak47")                             -- 拿上枪
local ok = numen.scan.sight(foe)                        -- 确认看得见
if ok.visible then tacz.gun.shoot(foe) end              -- 开一枪
```

`tacz.gun.shoot(目标)` 是一次扣扳机。它不吃距离参数、也不走路;打不打得中看你的位置和视线。
`shoot` 的返回会说这一枪的结果——`success` 打出去了,`no_ammo` 没子弹,`cool_down` 还在冷却,
`not_draw`/`not_gun` 是手上没拿枪。看结果再决定下一发。

## 换弹 / 开火模式 / 开镜

```lua
tacz.gun.reload()        -- 换弹
tacz.gun.fire_select()   -- 单发/全自动/连发之间轮换
tacz.gun.aim(true)       -- 开镜
tacz.gun.aim(false)      -- 收镜
```

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
- 把开枪当成唯一手段:近处、弹药不足时,用 numen.fight.attack 近战更省
