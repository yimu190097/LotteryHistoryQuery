# v22.0 变更说明

## 核心修复（严格按需求）
### 1. 查询结果页 - 只改展示奖名，元数据绝对不变
- LotteryMatcher.match() 重构：
  - 每期 draw 仍然使用自己真实 ruleVersionKey 独立计算命中（不跨期、不串规则）
  - 输出 QueryResultItem 时，按最新政策 config.rules 的全奖级结构排序展示
  - 跨版本 (matchPrimary, matchSecondary) 相同的 bucket，合并到最新版同名奖项行
  - 严格保证：matches: List<LotteryDraw> 中每个 draw 的期号、开奖日期、真实 ruleVersionKey、真实奖项数据一字不改
  - 尾部 bucket：旧政策特有的 (p,s) 在最新政策中找不到映射时，保留原奖项名独立展示
  - 未知版本桶：resolveRuleVersion == null 的期数显式标注【元数据缺失·版本不明】绝不悄悄丢弃

### 2. QueryResultItem 支持跨版本合并
- 字段 count / matches 从 val 改为 var，仅允许：
  - 跨版本合并时追加真实 draw 引用到 matches 列表
  - count 累加 draw 数量
  - 绝不修改 LotteryDraw 对象本身的任何字段

### 3. 期号详情页 - IssueSearchDialog 大乐透追加投注信息全奖级补齐
- 用最新政策 cfg.rules（如 DLT 2026 = 7 级）全量遍历
- 基本投注：优先 allPrizeTiers[i]，fallback 到历史字段 first/second
- 追加投注：appendPrizeTiers[i] 按索引 i 对应每一级，补齐三~九等奖追加，不再只显示一二等
- 点击「查看本期全部奖项详情」后 HistoryDialog 依然按该期真实 ruleVersionKey 分组展示真实政策

### 4. 构建环境修复
- buildToolsVersion = 34.0.0（解决找不到 25.0.2 崩溃）
- gradle.properties 固定 JDK17 路径（AGP8 不支持 JDK25）
- release.keystore 重新生成，assembleRelease 可正常构建签名
