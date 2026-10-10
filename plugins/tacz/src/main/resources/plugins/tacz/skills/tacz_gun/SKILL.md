---
name: tacz_gun
description: 用 TACZ 枪械作战。需要开枪、射击、清怪、自卫、或者主人让你"用枪打"时使用。前提是你背包里已经有 TACZ 枪和弹药。
---

# Skill: tacz_gun

装了 TACZ 之后,`tacz.gun` 组里的函数能让你拿枪、开枪、换弹、切开火模式、开镜。
每个怎么写,用 `numen.api.help("tacz.gun.shoot")` 查;这里只讲什么时候用、怎么连起来用。

## 开枪前先看手上有什么

`tacz.gun.options()` 一次给你:装了哪些枪(枪 id 清单)、你主手现在拿的是哪把、弹匣里几发、
膛内有没有子弹、是否在换弹/拉栓/疾跑、射击冷却还剩多少毫秒。

**平时不用盯着状态**——`tacz.gun.shoot` 会自己把枪摆对(拉栓、换弹、等冷却都替你做)。要看状态的时候,
`options()` 才用得上(比如打完一场仗清点弹药)。

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

## 走位 + 开枪

枪是远程,站远一点也能打,但要看得见、要站定(疾跑中开不了枪,shoot 会自己站定):

```lua
numen.move.to(foe.pos, {arrive = "near", range = 10})   -- 走到目标 10 格附近,到地方身体自己会站定
tacz.gun.equip("tacz:ak47")                             -- 拿上枪
local ok = numen.scan.sight(foe)                        -- 确认看得见
if ok.visible then
  local shot = tacz.gun.shoot(foe)                      -- 扣一次扳机,这一发自己会把枪准备好
end
```

**`tacz.gun.shoot(目标)` 一次就是完整的一发**,它自己在服务端把枪摆对:

- 手动枪膛里没子弹 → 它自己拉栓再打
- 弹匣空了 → 它自己换弹(前提是你包里带着对应弹药)
- 还在冷却/换弹/拉栓 → 它等完再打
- 你在疾跑 → 它自己站定再打

打出去就返回 `result = "success"`;实在开不了(包里没有对应弹药、手上没枪)会明确失败并说明原因,
看它说的话再行动——没弹药就换一把枪、或者跟主人要弹药。

**连发**:自动枪想连打几发,就调几次 `tacz.gun.shoot`(每次一发,等每次返回再调下一次)。

## 换弹 / 开火模式 / 开镜(精细控制用)

```lua
tacz.gun.reload()        -- 手动换弹:只"开始",换没换完看 options().reloading
tacz.gun.bolt()          -- 手动拉栓:shoot 已经替你拉,这是精细控制用
tacz.gun.fire_select()   -- 单发/全自动/连发之间轮换
tacz.gun.aim(true)       -- 开镜(减散布,可选)
tacz.gun.aim(false)      -- 收镜
```

**注意:客户端设置里的"自动换弹"对你无效**——你没有客户端,换弹永远要靠 `tacz.gun.reload()` 或 `shoot` 自己换。

## 避免误伤(最重要的一条)

开枪是伤人,**对谁开枪由权限层裁决**:你朝主人的宠物、命名过的动物、或者村民开枪,权限层会拦下来或先问主人。
所以你不要自己判断"这枪能不能打",正常瞄准目标开枪就行,不该打的自然打不出去。

但你自己也要守两条:
- 开枪前扫一眼 `numen.scan.entities("player", {radius = 16})`,别把主人卷进你和怪之间的弹道。
- 别朝会爆炸的东西(苦力怕)贴脸打,站爆炸波及范围之外再打。

## 别做的事

- 手上没枪、背包里没枪就假装开枪
- 对着墙后的敌人一直射(先 numen.scan.sight + numen.move.to 换位置)
- 同一只打不动的目标反复调 shoot——看失败原因,没弹药就是没弹药,换办法(近战/换枪/要弹药)
- 把开枪当成唯一手段:近处、弹药不足时,用 numen.fight.attack 近战更省
