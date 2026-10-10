---
name: tlm_gun
description: 主人要远程火力、清怪、自卫,或让你指挥女仆用枪时使用。女仆有 TACZ 枪就让她打,你只做指挥,不自己扣扳机。
---

# 女仆用枪

车万女仆装好 TACZ 之后,女仆有一个「枪械攻击」工作模式(`touhou_little_maid:gun_attack`):她主手持 TACZ 枪,
会自己找敌人、走位、瞄准、开枪、拉栓、换弹、开镜——全自动,不用你替她扣扳机。你的活是三步:给她枪和弹药、
切模式、放她打。

## 第一步:找到你的女仆

`tlm.maid.list` 列出你名下的女仆。没有就先驯服一只:拿着蛋糕右键野生女仆
(`numen.use.entity(812, {item = "minecraft:cake"})`,编号换成 `numen.scan.entities` 给她的那个;先走到她旁边)。
没有女仆也没有蛋糕,就告诉主人:需要先养一只女仆。

## 第二步:给她枪和弹药

女仆要主手持 TACZ 枪、背包里带匹配的弹药(她打空弹匣会自己从背包找弹药换,找不到就不打了)。

1. 走到她旁边(界面要 7 格内),`tlm.maid.open(812)` 打开她的背包页。
2. `numen.gui.view` 看格子编号(你的背包在前,她的在后)。
3. `numen.gui.move(3, 42)` 把你包里的枪放进她主手那格,弹药放进她背包的格子;
   整叠用 `numen.gui.quick(3)`。枪要放主手,弹药放背包格。
4. `numen.gui.close` 收工。

(主人愿意的话,让他直接开女仆界面放更快——你就说缺什么。)

放好之后 `tlm.maid.info(812)` 确认:她的 `equipment.mainhand` 是枪,`tasks` 里枪械攻击那一条的
`works_with.has_gun` 是 true。

## 第三步:切到枪械攻击模式

`tlm.maid.task("touhou_little_maid:gun_attack", {maid = 812})`

回执会读回她现在的模式;没切过去会告诉你车万女仆的规矩怎么说(多半是缺枪或缺弹药),照它说的补。

## 第四步:决定打谁

默认规矩:怪物 hostile(见了就打),驯服动物和村民 friendly,其余 neutral。一般不用改。
要指定打谁、不打谁,用 `tlm.maid.set_targets`:

- `tlm.maid.set_targets(812, {set = {["minecraft:creeper"] = "friendly"}})` 让她别打苦力怕。
- `tlm.maid.set_targets(812, {remove = {"minecraft:zombie"}})` 恢复默认。

**避免误伤**:玩家不在女仆的攻击名单里,她不会主动打主人或你。你只需提醒主人别站到她和怪中间。

## 打起来之后

- 她自己在远处开火,你不用每刻管她;过一阵用 `tlm.maid.info(812)` 看她状态(血量、还打不打)。
- 她弹药用完了会停下:再用第二步给她补弹药。
- 要她停,把模式切回别的(如 `touhou_little_maid:idle`)或拿走她主手的枪。

## 别做的事

- 女仆没枪就切枪械攻击模式(会失败,先给枪)
- 只给枪不给弹药(打完弹匣她就停了)
- 把模式来回切(一次定好,缺什么补什么)
