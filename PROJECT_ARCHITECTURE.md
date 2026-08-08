# 彩票历史开奖查询系统 - 项目架构文档

> 版本：v22.0 | 更新日期：2026-08-08 | 仓库：https://github.com/yimu190097/LotteryHistoryQuery

---

## 一、项目概述

国内彩票历史开奖查询 Android App，支持双色球、大乐透、福彩3D、七乐彩、排列三、排列五、七星彩、快乐8 共 8 个彩种。核心特性：**按期政策自适应展示**——不同历史阶段官方规则不同时，每期开奖数据按当期适用的规则版本正确展示，避免规则变更后历史数据展示错位。

---

## 二、技术栈

| 层级 | 技术 | 说明 |
|---|---|---|
| 语言 | Kotlin | 全量 Kotlin，无 Java |
| 构建 | Gradle 8.5 + AGP 8.2.2 | Kotlin DSL (build.gradle.kts) |
| UI | Android View (代码动态构建) | 无 XML Jetpack Compose，Fragment + Dialog 架构 |
| 数据库 | Room (SQLite) | 9 张表，版本 11，含 Migration |
| 网络 | HttpURLConnection | 直连 17500.cn 数据源 |
| 解析 | jxl 2.6.12 (Excel) + 自研文本解析器 | 支持 OLE2 二进制 Excel 和纯文本两种格式 |
| 并发 | Kotlin Coroutines + Mutex | 全局单 Mutex 保护数据层读写 |
| 插件 | detekt 1.23.6 / spotless 6.25.0 / jacoco / versions 0.51.0 | 静态分析 + 格式化 + 覆盖率 + 依赖检查 |

---

## 三、项目目录结构

```
app/src/main/java/com/lottery/history/
├── model/               # 数据模型 + 彩种配置
│   ├── LotteryType.kt        # 彩种配置 + 规则版本定义 + 版本定位引擎
│   └── LotteryModels.kt      # LotteryDraw / PrizeTierEntry / QueryResultItem 等数据类
├── network/             # 网络层 + 数据解析
│   ├── LotteryRepository.kt  # HTTP 请求 + 缓存策略
│   └── LotteryXlsParser.kt   # 官方数据解析器（OLE2 Excel + 文本双模式）
├── db/                  # Room 数据库（9 张表）
│   ├── LotteryDatabase.kt     # RoomDatabase 定义 + Migration(10→11)
│   ├── LotteryDrawEntity.kt   # 开奖数据主表
│   ├── RuleVersionCatalogEntity.kt  # 规则版本目录表
│   ├── MatchRuleDefEntity.kt  # 匹配规则定义表
│   ├── PrizeTierEntity.kt     # 奖级明细表（结构化）
│   ├── QueryRecordEntity.kt   # 用户查询记录
│   ├── UserEntity.kt          # 用户信息
│   ├── QuotaEntity.kt         # 配额管理
│   ├── PendingSyncEntity.kt   # 待同步队列
│   ├── ChatMessageEntity.kt   # 聊天消息
│   └── *Dao.kt                # 各表 DAO
├── data/                # 数据管理层
│   ├── LotteryDataManager.kt  # 核心数据管理器（缓存 + DB + 网络协调）
│   ├── QueryRecordManager.kt  # 查询记录管理
│   ├── AuthRepository.kt      # 认证
│   ├── QuotaRepository.kt     # 配额
│   └── SessionStore.kt        # 会话
├── util/                # 工具层
│   ├── LotteryMatcher.kt      # 选号命中匹配引擎（按政策版本分组）
│   ├── BallTextHelper.kt      # 号码球渲染
│   └── VoiceHelper.kt         # 语音
├── ui/                  # UI 层
│   ├── LotteryFragment.kt     # 主页面（选号 + 命中统计展示 + 分组标题行）
│   ├── DrawDetailDialog.kt    # 单期详情弹窗（政策卡 + 期号绑定 + 奖级列表）
│   ├── LatestDrawsDialog.kt   # 最新一期展示（徽章期号绑定）
│   ├── HistoryDialog.kt       # 历史列表
│   ├── IssueSearchDialog.kt   # 期号搜索
│   ├── QueryRecordDialog.kt   # 查询记录
│   ├── AuthDialog.kt          # 登录
│   ├── CustomerServiceActivity.kt  # 客服
│   └── VoiceCallActivity.kt   # 语音通话
├── adapter/             # 列表适配器
├── widget/              # 自定义控件（FlowLayout）
├── work/                # WorkManager 定时任务
│   ├── DailyUpdateWorker.kt   # 每日自动更新
│   └── SyncWorker.kt          # 数据同步
├── LotteryApp.kt        # Application
└── MainActivity.kt      # 入口
```

---

## 四、核心架构设计

### 4.1 分层架构

```
┌─────────────────────────────────────────┐
│              UI 层 (ui/)                │
│  LotteryFragment / DrawDetailDialog     │
│  LatestDrawsDialog / HistoryDialog      │
│  ← 按 ruleVersionKey 动态渲染奖项       │
├─────────────────────────────────────────┤
│           数据管理层 (data/)             │
│  LotteryDataManager                     │
│  ← 内存缓存 + DB 读写 + 网络协调         │
│  ← Mutex 保护并发，ensureInitializedLocked 防死锁
├─────────────────────────────────────────┤
│      网络层 (network/) + 解析器          │
│  LotteryRepository (HTTP)               │
│  LotteryXlsParser (OLE2/文本双模式)     │
│  ← 解析时确定 ruleVersionKey 并持久化    │
├─────────────────────────────────────────┤
│           数据库层 (db/)                 │
│  Room Database v11, 9 张表              │
│  ← 开奖数据 + 规则目录 + 奖级明细分离    │
└─────────────────────────────────────────┘
```

### 4.2 期政策自适应核心流程

```
解析阶段（网络/Seed → DB）:
  1. 获取当期 issue + date
  2. config.rulesForDate(date, issue) → 确定当期 RuleVersion
     - 优先级：date 非空 → 用 date 匹配
     - date 为空 → 从 issue 前缀推断年份（inferDateFromIssue）
     - 都为空 → 取最新版本保底
  3. 解析号码 + 奖级 + 销售额 + 奖池
  4. 计算 conditionalFlags（福运奖 ON/OFF/HOLD、DLT 上浮 NORMAL/UP）
  5. 计算 tierMatchStatus（MATCH/FEWER/MORE/MISMATCH）
  6. 将 ruleVersionKey + allPrizeTiers + conditionalFlags 一起写入 DB

展示阶段（DB → UI）:
  1. draw.resolveRuleVersion(config) → 取当期 RuleVersion
     - 优先级：ruleVersionKey（DB 持久化值）> rulesForDate(date, issue) > 最新版保底
  2. 按 RuleVersion.rules 渲染奖项名 + 奖金 + 中奖注数
  3. 按 conditionalFlags 渲染条件奖级标志（停发灰底 / 上浮高亮）
  4. LotteryMatcher 按 BucketKey(ruleVersionKey, tier) 独立分组统计
  5. LotteryFragment 在版本切换时插入绿色分组标题行
```

### 4.3 版本定位三级兜底机制

```
LotteryType.rulesForDate(date, issue):
  ├── date 非空 → 直接用 date 匹配 ruleVersions（firstOrNull { date >= effectiveFromDate }）
  ├── date 为空但 issue 非空 → inferDateFromIssue(issue, code) 推断年份
  │     ├── SSQ: issue 前4位 = 年份（例 2026090 → 2026）
  │     ├── DLT: issue 前2位补 "20" = 年份（例 26088 → 2026）
  │     └── 其他: 前4位≥1900 当年份，否则前2位补20
  └── 都为空 → ruleVersions.first()（最新版保底）

LotteryDraw.resolveRuleVersion(config):
  ├── ruleVersionKey 非空 → 从 config.ruleVersions 查找匹配的 key
  └── ruleVersionKey 为空 → 回退到 rulesForDate(date, issue)
```

---

## 五、数据解析设计

### 5.1 数据源

| 彩种 | URL | 格式 |
|---|---|---|
| 双色球 | `http://data.17500.cn/ssq_desc.txt` | 空格分隔文本 / OLE2 Excel |
| 大乐透 | `http://data.17500.cn/dlt2_desc.txt` | 同上 |
| 福彩3D | `http://data.17500.cn/3d_desc.txt` | 同上 |
| 七乐彩 | `http://data.17500.cn/7lc_desc.txt` | 同上 |
| 排列三 | `http://data.17500.cn/pl3_desc.txt` | 同上 |
| 排列五 | `http://data.17500.cn/pl5_desc.txt` | 同上 |
| 七星彩 | `http://data.17500.cn/7xc_desc.txt` | 同上 |
| 快乐8 | `http://data.17500.cn/kl8_desc.txt` | 同上 |

### 5.2 解析器架构（LotteryXlsParser）

```
输入 InputStream
  │
  ├── 嗅探前4字节：是否 OLE2 头 (0xD0CF11E0)
  │     ├── 是 → binaryXlsToSpaceText(): jxl 读 Excel，每行 cell 空格拼接成文本行
  │     └── 否 → 直接当 UTF-8 文本
  │
  └── parseText(): 统一文本解析
        ├── 逐行处理，issueRegex 过滤非数据行（表头/空行）
        ├── 字段提取顺序：
        │     [0] 期号  [1] 开奖日期
        │     [2..N] 主号码（parsePrimaryCount 个）
        │     [N..M] 次号码（parseSecondaryCount 个，若有）
        │     [M..] extraFieldCount 个额外字段（销售额/奖池/出球顺序）
        │     [..] prizeTierPairCount 对 (中奖注数, 单注奖金) × 基本投注
        │     [..] appendTierPairCount 对 × 追加投注（仅大乐透）
        │
        ├── parseNumberSafe(): 安全数字解析
        │     ├── 纯整数/小数 → 截断取整（8000.0 → 8000）
        │     ├── 美式千分位 (84,337,222.00) → 去逗号截断
        │     ├── 欧式小数 (84.337.222,00) → 去.转,截断
        │     └── 畸形格式 → 拒绝返回 null
        │
        ├── rulesForDate(date, issue) → 确定当期 RuleVersion
        │
        ├── 提取 conditionalFlags：
        │     ├── SSQ 福运奖：有第7对数据→ON / 奖池≥15亿→ON / <3亿→OFF / 中间→HOLD
        │     └── DLT 上浮：奖池≥8亿→UP / <8亿→NORMAL / 未知→HOLD
        │
        └── 输出 LotteryDraw（含 ruleVersionKey + allPrizeTiers + conditionalFlags + ...）
```

### 5.3 增量刷新保护策略

网络刷新时，若新数据含 `-`（占位符）而本地已有更完整的版本（`tierMatchStatus=MATCH` 且 nonNullTiers 更多），保留本地数据不被覆盖。避免官方数据源先出前几对、次日补全时的数据回滚。

---

## 六、数据库设计

### 6.1 数据库概览

- 数据库：`lottery.db`（Room/SQLite）
- 版本：11（含 Migration 10→11）
- 表数量：9 张

### 6.2 表结构详解

#### 表 1：`lottery_draws`（开奖数据主表）

| 列名 | 类型 | 说明 |
|---|---|---|
| `issue` | TEXT (PK) | 期号 |
| `type` | TEXT (PK) | 彩种代码（ssq/dlt/3d/...） |
| `primary` | TEXT | 主号码，逗号分隔（"1,5,12,23,28,33"） |
| `secondary` | TEXT | 次号码，逗号分隔（"7" 或 "3,9"） |
| `date` | TEXT? | 开奖日期（"2026-08-07"） |
| `firstPrizeCount` | INT? | 一等奖注数（便捷兼容字段） |
| `firstPrizeAmount` | LONG? | 一等奖单注金额（元） |
| `secondPrizeCount` | INT? | 二等奖注数 |
| `secondPrizeAmount` | LONG? | 二等奖单注金额 |
| `allPrizeTiers` | TEXT? | 全部奖级序列化："count:amount,count:amount,..." |
| `ruleVersionKey` | TEXT? | 规则版本标识（"ssq_20260201"），解析时确定 |
| `actualTierCount` | INT? | 实际解析到的奖级对数 |
| `tierMatchStatus` | TEXT? | 结构审计：MATCH/FEWER/MORE/MISMATCH |
| `jackpotAmount` | LONG? | 当期奖池金额（元） |
| `salesAmount` | LONG? | 当期全国销售额（元） |
| `appendPrizeTiers` | TEXT? | 追加投注奖级（仅大乐透） |
| `parseSource` | TEXT? | 解析来源：SEED/NET/MIGRATE/SEED_INCOMPLETE |
| `parseAt` | LONG? | 解析时间戳（毫秒） |
| `parserVersion` | INT? | 解析器版本号 |
| `conditionalFlagsJson` | TEXT? | 条件奖级开关：key=value&key2=value2 |

主键：`(issue, type)` 联合主键

#### 表 2：`rule_version_catalog`（规则版本目录表）

| 列名 | 类型 | 说明 |
|---|---|---|
| `ruleVersionKey` | TEXT (PK) | 规则版本唯一标识（"dlt_20260131"） |
| `code` | TEXT | 彩种代码 |
| `effectiveFromDate` | TEXT | 生效日期（"2026-01-31"） |
| `policyLabel` | TEXT | 政策标签（"2026年新规·7级"） |
| `changeNote` | TEXT | 变更说明 |
| `realTiersToUse` | INT | 去重后奖级数 |
| `prizeTierPairCount` | INT | 结构化奖级对数 |
| `extraFieldCount` | INT | 号码后额外字段数 |
| `appendTierPairCount` | INT | 追加投注奖级对数 |
| `snapshotAt` | INT | 快照时间戳 |

#### 表 3：`match_rule_def`（匹配规则定义表）

| 列名 | 类型 | 说明 |
|---|---|---|
| `ruleVersionKey` | TEXT (PK) | 所属规则版本 |
| `dedupIndex` | INT (PK) | 去重索引（同奖名共享） |
| `ruleIndex` | INT (PK) | 规则在版本中的原始下标 |
| `matchPrimary` | INT | 主号码命中数 |
| `matchSecondary` | INT | 次号码命中数 |
| `description` | TEXT | 规则描述 |
| `prizeName` | TEXT | 奖项名（"一等奖"/"七等奖"） |
| `fixedAmountYuan` | INT? | 固定奖金（元），null=浮动 |
| `conditionalKey` | TEXT? | 条件奖级 key（"ssq_fuyun_onoff"） |

主键：`(ruleVersionKey, dedupIndex, ruleIndex)`

#### 表 4：`lottery_prize_tier`（奖级明细表 - 结构化）

| 列名 | 类型 | 说明 |
|---|---|---|
| `issue` | TEXT (PK) | 期号 |
| `type` | TEXT (PK) | 彩种代码 |
| `tierGroup` | TEXT (PK) | 奖级组：BASE（基本投注）/ APPEND（追加投注） |
| `tierIndex` | INT (PK) | 奖级序号（0=一等奖） |
| `count` | INT | 中奖注数 |
| `amount` | INT | 单注奖金（元） |
| `updatedAt` | INT | 更新时间戳 |

主键：`(issue, type, tierGroup, tierIndex)`

#### 表 5-9：功能表

| 表名 | 用途 | 主键 |
|---|---|---|
| `query_records` | 用户选号查询记录 | id (自增) |
| `users` | 用户信息 | userId |
| `quotas` | 配额管理 | userId |
| `pending_syncs` | 待同步队列 | id (自增) |
| `chat_messages` | 客服聊天消息 | id (自增) |

### 6.3 数据库迁移

**Migration(10, 11)**：
- 新建 `rule_version_catalog` 表
- 新建 `match_rule_def` 表
- 新建 `lottery_prize_tier` 表
- `lottery_draws` 表新增 4 列：`parseSource`、`parseAt`、`parserVersion`、`conditionalFlagsJson`

### 6.4 多表设计原则

开奖数据采用**主表 + 明细表 + 目录表**三表分离设计：

1. **`lottery_draws`（主表）**：存原始数据 + 序列化奖级字符串 + 元数据。便于快速读取和缓存。
2. **`lottery_prize_tier`（明细表）**：结构化存储每个奖级，支持精确查询和统计。`tierGroup` 区分基本投注/追加投注。
3. **`rule_version_catalog` + `match_rule_def`（目录表）**：规则版本和匹配规则的定义快照，独立于开奖数据。规则变更时只更新目录表，不影响历史开奖数据的展示。

这样设计的好处：
- 规则变更后，历史数据仍按解析时的 `ruleVersionKey` 展示，不会错位
- 奖级数据既有序列化字符串（快速读取）又有结构化表（精确查询）
- 条件奖级标志（`conditionalFlagsJson`）随每期数据持久化，展示时直接读取，不依赖运行时重新计算

---

## 七、逻辑展示设计

### 7.1 选号命中匹配引擎（LotteryMatcher）

```
输入：config + selectedPrimary + selectedSecondary + history(全部历史开奖)
  │
  ├── 1. 遍历每期 draw，用 draw.resolveRuleVersion(config) 取当期规则
  │     ├── 检查 conditionalFlags：福运奖 OFF → 跳过 3+0 规则
  │     ├── 计算 primaryCount / secondaryCount 命中数
  │     ├── 匹配 ruleVersion.rules 中的规则，命中后 break（每期只算最高奖级）
  │     └── 写入 buckets[BucketKey(ruleVersionKey, matchP, matchS, prizeName)]
  │
  ├── 2a. 按最新版 config.rules 顺序输出
  │     ├── 找到所有版本中匹配 (matchP, matchS, prizeName) 的 bucket
  │     ├── 标记 visited
  │     └── 每个版本独立输出一行 QueryResultItem（带 sourceRuleVersionKey）
  │
  └── 2b. 旧版本中剩余、最新版已不存在的奖级（如 DLT 2019 八/九等奖）
        ├── 追加到尾部
        └── 带 policyLabel 标识
```

**核心设计**：`BucketKey = (ruleVersionKey, matchPrimary, matchSecondary, prizeName)` 四元组。同名奖级在不同规则版本中分开统计，避免跨阶段奖级错位。

### 7.2 分组展示（LotteryFragment）

多版本彩种（如大乐透有 5 个规则版本）在选号命中统计结果展示时：

1. 遍历 `results` 列表，检查 `item.sourceRuleVersionKey`
2. 版本切换时插入绿色分组标题行：
   ```
   ▌【超级大乐透2026新规（7级奖池联动）】7级规则  生效日：2026-01-31起
     七等奖 0+2 → 中105次（5/7元）
   ▌【超级大乐透2019版（9级）】9级规则  生效日：2019-02-18起
     七等奖 4+0 → 中160次（100元）
     八等奖 3+1/2+2 → 中NN次
     九等奖 0+2 → 中126次（5元）
   ```
3. 单版本彩种（3D/P3/P5 等）不显示分组标题

### 7.3 政策徽章期号绑定

- **LatestDrawsDialog**：`draw.resolveRuleVersion(config)` 取当期规则 → 徽章显示 `政策标签｜期号XXX`
- **DrawDetailDialog**：顶部绿卡显示 `【本期适用】政策标签  期号：XXX / 生效日期：YYYY-MM-DD起`
- 单版本彩种（`config.ruleVersions.size == 1`）徽章自动隐藏

### 7.4 条件奖级标志展示

| 条件 | 展示效果 |
|---|---|
| SSQ 福运奖 ON | 正常显示福运奖行（3+0=5元） |
| SSQ 福运奖 OFF | 整行浅灰 + "奖池未达门槛，本奖项停发" |
| SSQ 福运奖 HOLD | 正常显示但标注"维持上期状态" |
| DLT 上浮 NORMAL | 三~七等奖正常金额（5000/300/150/15/5） |
| DLT 上浮 UP | 三~七等奖绿色高亮 + "本期奖池≥8亿已上浮¥6666/380/200/18/7" |

---

## 八、数据流全景

```
App 启动
  │
  ├── LotteryDataManager.ensureInitialized()
  │     ├── 检查 DB 是否有 ssq/dlt seed 数据
  │     │     └── 无 → importSeed(): 读 raw/ssq_seed.txt, raw/dlt_seed.txt
  │     │           └── 从 issue 前缀推导 ruleVersionKey
  │     ├── ensureRuleCatalogPersisted(): 规则目录写入 DB
  │     └── 修补 seed 来源但缺元数据的记录 → SEED_INCOMPLETE
  │
  ├── loadCaches(): 全部彩种从 DB 加载到内存缓存
  │
  ├── UI 层读取缓存展示
  │
  └── 用户下拉刷新 → refresh()
        ├── 每个彩种独立拉取 LotteryRepository.fetchHistory()
        ├── LotteryXlsParser.parse() → 解析出 List<LotteryDraw>
        ├── 增量保护：含 '-' 的新数据不覆盖本地完整版本
        ├── 写入 lottery_draws 表 + lottery_prize_tier 表
        └── 更新内存缓存
```

---

## 九、彩种规则版本配置

| 彩种 | 规则版本数 | 版本列表 |
|---|---|---|
| 双色球 (SSQ) | 2 | ssq_20260201（含福运奖7级）/ ssq_20030216（经典6级） |
| 大乐透 (DLT) | 5 | dlt_20260131（7级）/ dlt_20190218（9级）/ dlt_20140505（6级）/ dlt_20091017（8级）/ dlt_20070528（上市首版8级） |
| 福彩3D (FC3D) | 1 | 3d_stable（现行3档） |
| 七乐彩 (QLC) | 1 | 7lc_stable（现行7级） |
| 排列三 (P3) | 1 | p3_stable（现行3档） |
| 排列五 (P5) | 1 | p5_stable（现行1档） |
| 七星彩 (QXC) | 2 | qxc_20201011（固定奖升级）/ qxc_2004（经典6级） |
| 快乐8 (KL8) | 1 | kl8_stable（选十玩法7档） |

---

## 十、开发插件配置

| 插件 | 版本 | 用途 | 配置位置 |
|---|---|---|---|
| Detekt | 1.23.6 | Kotlin 静态代码分析 | build.gradle.kts (root) + app/build.gradle.kts |
| Spotless | 6.25.0 | 代码格式化 (ktfmt 0.46 kotlinlangStyle) | build.gradle.kts (root) |
| JaCoCo | (内置) | 单元测试覆盖率报告 | app/build.gradle.kts (debug variant) |
| Ben-Manes Versions | 0.51.0 | 依赖版本检查 | build.gradle.kts (root) |

---

## 十一、CI/CD

- **Workflow**：`.github/workflows/build-apk.yml`
- **触发**：push to main / 手动 dispatch
- **流程**：checkout → JDK 17 → Android SDK → Gradle 8.5 → assembleRelease → 上传 APK 到 GitHub Release
- **当前版本**：v24.1
- **Release 页**：https://github.com/yimu190097/LotteryHistoryQuery/releases/tag/v24.1
- **APK 直链**：https://github.com/yimu190097/LotteryHistoryQuery/releases/download/v24.1/LotteryHistoryQuery_v24.1.apk
