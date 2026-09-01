// 从官方 sporttery 数据中提取各政策阶段的奖级结构（基本+追加）
const files = ['_official_dlt.json', '_off_38.json', '_off_62.json', '_off_85.json', '_off_98.json'];
for (const f of files) {
  const d = require('./' + f);
  const v = d.value || {};
  const draw = v.list[0];
  console.log('\n===== ' + f + ' 期号 ' + draw.lotteryDrawNum + ' ' + draw.lotteryDrawTime + ' =====');
  console.log('开奖结果: ' + draw.lotteryDrawResult);
  console.log('奖池: ' + draw.poolBalanceAfterdraw + ' 销售额: ' + draw.totalSaleAmount);
  const pl = (draw.prizeLevelList || []).slice().sort((a, b) => a.sort - b.sort);
  for (const p of pl) {
    console.log(
      '  sort=' + String(p.sort).padEnd(5) +
      ' group=' + String(p.group).padEnd(6) +
      ' ' + String(p.prizeLevel).padEnd(14) +
      ' 注=' + String(p.stakeCount).padEnd(10) +
      ' 单注=' + String(p.stakeAmount).padEnd(14) +
      ' 总额=' + p.totalPrizeamount
    );
  }
}