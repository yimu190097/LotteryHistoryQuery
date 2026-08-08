# 彩票历史开奖查询系统

> 最新版本：**v22.0** | 更新日期：2026-08-08
> 仓库：https://github.com/yimu190097/LotteryHistoryQuery

国内彩票历史开奖查询 Android App。支持 **双色球、大乐透、福彩3D、七乐彩、排列三、排列五、七星彩、快乐8** 共 8 个彩种。

## ✨ 核心特性

- **按期政策自适应展示**：不同历史阶段官方规则不同，每期开奖数据严格按**当期**适用的规则版本展示，绝不用最新版规则去套历史期，避免规则变更后历史期展示错位误导。
- **政策版本分组**：用户选号命中结果按政策版本独立分组展示，版本不明时显式标红警告（`元数据缺失·版本不明`）。
- **期号-政策绑定**：最新一期、历史列表、搜索详情、单期详情 4 处 UI 的政策徽章均已**绑定期号**，明确展示「哪一期对应哪一版政策」。
- **真数据真解析**：奖级注数、单注金额、销售额、奖池、追加投注、条件奖级（福运奖/派奖浮动）均来自官方数据源，严格校验格式。
- **全链路 Long 类型**：中奖注数、销量、奖池、金额等字段全部 Long，杜绝 Int(21.47 亿) 溢出截断。
- **零精度损失数字解析**：销售额/奖池/金额解析采用纯字符串截断，不经过 Double 中转，消除 53bit 尾数精度风险。
- **Room schema 导出审计**：exportSchema=true，9 张表结构 JSON 入库，迁移 SQL 可审查。

## 📥 APK 下载

👉 **[v22.0 最新版 APK 直接下载](https://github.com/yimu190097/LotteryHistoryQuery/releases/download/v22.0/LotteryHistoryQuery-v22.0.apk)**

全部版本见 [Releases 页面](https://github.com/yimu190097/LotteryHistoryQuery/releases)。

## 🛠 技术栈

| 层级 | 技术 | 说明 |
|---|---|---|
| 语言 | Kotlin | 全量 Kotlin |
| 构建 | Gradle 8.5 + AGP 8.2.2 | Kotlin DSL `build.gradle.kts` |
| UI | Android View（代码动态构建）| Fragment + Dialog 架构 |
| 数据库 | Room (SQLite) | 9 张表，版本 12，含 Migration 10→11→12 |
| 网络 | HttpURLConnection | 直连 17500.cn 官方数据源 |
| 解析 | jxl 2.6.12 (OLE2 Excel) + 自研文本双模式 | 支持美式/欧式千分位 |
| 并发 | Kotlin Coroutines + Mutex | 全局单 Mutex 保护数据层读写 |
| 插件 | detekt 1.23.6 / spotless 6.25.0 / jacoco / versions 0.51.0 | 静态分析 / 格式化 / 覆盖率 / 依赖检查 |

## 🗂 目录结构

```
app/src/main/java/com/lottery/history/
├── model/               # 数据模型 + 彩种配置
│   ├── LotteryType.kt        # 彩种 + 规则版本 + 版本定位引擎
│   └── LotteryModels.kt      # LotteryDraw / PrizeTierEntry / QueryResultItem
├── network/             # 网络 + 解析
│   ├── LotteryRepository.kt  # HTTP + 缓存策略 + 真奖级校验
│   └── LotteryXlsParser.kt   # OLE2 Excel + 纯文本双解析器
├── db/                  # Room（9 张表）
│   ├── LotteryDatabase.kt     # DB + Migration
│   ├── LotteryDrawEntity.kt   # 开奖主表
│   ├── RuleVersionCatalogEntity.kt  # 规则版本目录
│   ├── MatchRuleDefEntity.kt  # 匹配规则定义
│   ├── PrizeTierEntity.kt     # 奖级明细（结构化）
│   ├── QueryRecordEntity.kt   # 查询记录
│   ├── UserEntity.kt / QuotaEntity.kt / ChatMessageEntity.kt / PendingSyncEntity.kt
│   └── *Dao.kt
├── data/                # 数据管理层
│   └── LotteryDataManager.kt  # 缓存 + DB + 网络协调（全局 Mutex）
├── util/LotteryMatcher.kt     # 选号命中匹配引擎（按政策版本分组）
├── ui/                  # UI 层
│   ├── LotteryFragment.kt     # 主页面（选号 + 分组统计）
│   ├── DrawDetailDialog.kt    # 单期详情（政策卡 + 期号绑定）
│   ├── LatestDrawsDialog.kt   # 最新一期（徽章绑定期号）
│   ├── HistoryDialog.kt / IssueSearchDialog.kt / QueryRecordDialog.kt
│   └── 客服/语音通话 Activity
├── adapter/ widget/ work/     # 适配器 / 自定义控件 / WorkManager
└── MainActivity.kt / LotteryApp.kt
```

## 🏗 架构 & 设计

完整的架构说明、数据解析流程、数据库设计、政策版本定位逻辑，请查看：

📖 **[PROJECT_ARCHITECTURE.md](./PROJECT_ARCHITECTURE.md)**

## 🔧 本地构建

```bash
./gradlew assembleDebug        # Debug 包
./gradlew assembleRelease      # Release 包 (6.6MB)
./gradlew detekt               # 静态代码分析
./gradlew spotlessCheck        # 格式检查
./gradlew spotlessApply        # 自动格式化
./gradlew dependencyUpdates    # 检查新版本依赖
./gradlew jacocoDebugUnitTestReport  # 单元测试覆盖率报告
```

构建成功产物：`app/build/outputs/apk/release/app-release.apk`

## 📜 版本记录

### v22.0 (2026-08-08)
1. DLT 2007/2009/2014 三版规则补全 `appendTierPairCount`（老版本追加投注奖级不再丢失）
2. `parseNumberSafe` 纯字符串解析，零精度损失
3. DAO 字段 `@Volatile` 多线程可见性修复
4. seed fakeDate 01-01→06-15，SSQ 2026 新规期数首次导入正确定位
5. Room `exportSchema=true`，schema JSON 入版本控制
6. FC3D/P3 规则合并为直选单规则，详情页展示官方真实三档奖级
7. Int→Long 全链路 + resolveRuleVersion null 安全 + remove 最新版保底 + 红色元数据缺失警告

---

⚠️ **安全提醒**：签名密钥文件 `app/release.keystore` 绝不能入 git，已在 `.gitignore` 中屏蔽；如果你之前 push 过，务必重新生成新的签名密钥并在应用商店更新签名。
