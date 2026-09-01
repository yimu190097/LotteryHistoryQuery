# 彩票历史开奖查询系统

> 仓库: https://github.com/yimu190097/LotteryHistoryQuery
> 三端: 用户端App(`app/`) + 管理端App(`admin-app/`) + 后端服务(`server/`)
> 数据源: `http://data.17500.cn/{code}_desc.txt` (code: ssq/dlt2/3d/pl3/pl5/7xc/kl8/7lc)

## 项目结构

```
.
├── app/                  # 用户端 Android App (Kotlin)
├── admin-app/            # 管理端 Android App (Kotlin)
├── server/               # Node.js 后端 + Web管理面板 + 用户端网页版
│   ├── src/
│   │   ├── index.js      # Express 入口 (API + WebSocket + 开奖数据代理)
│   │   ├── db/           # SQLite (better-sqlite3)
│   │   ├── routes/       # auth/users/stats/config/chat/deploy
│   │   ├── ws/           # WebSocket 客服 + 语音通话
│   │   ├── middleware/   # JWT 鉴权
│   │   └── utils/        # 初始化脚本
│   └── public/
│       ├── web/          # 用户端网页版 (单页应用)
│       ├── css/ js/      # 管理后台前端
│       └── index.html    # 管理后台入口
├── deploy/               # 部署脚本 (deploy/update/backup/health-check/build-apk)
├── scripts/              # 验证工具 (verify_parser.py 等)
├── .github/workflows/    # CI: push main → 构建 Release APK
├── build.gradle.kts      # 根 Gradle (detekt + spotless + jacoco)
├── settings.gradle.kts   # 模块声明 (app + admin-app)
└── start.sh              # 一键启动 (npm install + node + ngrok)
```

## 部署流程

**禁止在 VM 上 `git pull`**（GitHub 直连超时）。正确流程:

1. 本地改代码 → `git push`
2. 触发 webhook 部署:

```bash
curl -s -X POST https://showbiz-unbridle-decent.ngrok-free.dev/api/deploy/webhook \
  -H "X-Webhook-Secret: <见交接文档>" \
  -H "Content-Type: application/json"
```

- 部署脚本走国内镜像 (ghfast.top / gh-proxy.com / raw.gitmirror.com)
- 30-120秒生效，查日志: `GET /api/deploy/webhook/log`

## 本地构建

```bash
# 后端
cd server && npm install && node src/index.js

# Android
./gradlew assembleDebug        # Debug 包
./gradlew assembleRelease      # Release 包
./gradlew detekt               # 静态分析
./gradlew spotlessApply        # 格式化

# 数据校验
python3 scripts/verify_parser.py
```

## 接口速查

| 接口 | 说明 |
|------|------|
| `GET /api/health` | 健康检查 |
| `GET /api/lottery/:code` | 开奖历史代理 (缓存+重试+熔断) |
| `POST /api/auth/login` | 管理员登录 |
| `/api/users/client/*` | 用户端登录/注册/配额/扣减 |
| `GET /api/stats/dashboard` | 仪表盘 |
| `/api/config` | 系统配置 |
| `/api/chat` | 客服消息 |
| `/api/apk/sync` + `/api/apk-list` | APK 同步与下载 |
| `POST /api/deploy/webhook` | 触发部署 |

## 技术栈

| 端 | 技术 |
|----|------|
| App | Kotlin, Room(SQLite), Coroutines, jxl(Excel解析), HttpURLConnection |
| Admin | Kotlin, OkHttp, Gson, Material Design |
| Server | Node.js, Express, better-sqlite3, JWT, WebSocket, multer |
| CI/CD | GitHub Actions (自动构建 APK + Release) |
| 质量 | detekt (静态分析), spotless (格式化), jacoco (覆盖率) |

## 关键注意事项

1. 仓库公开，**禁止提交 token/secret/keystore**
2. `deploy/update.sh` 的 FILES 列表必须与 `server/src` 实际文件一致
3. 数据源 code 正确值: `ssq/dlt2/3d/pl3/pl5/7xc/kl8/7lc`
4. VM 连 GitHub 超时，必须走 `raw.githubusercontent.com` 或国内镜像
5. `update.sh` 中途 `systemctl restart` 会杀掉部署进程自身，重启放最后

## APK 下载

[Releases 页面](https://github.com/yimu190097/LotteryHistoryQuery/releases/latest)

---

详细架构说明见 [PROJECT_ARCHITECTURE.md](./PROJECT_ARCHITECTURE.md)