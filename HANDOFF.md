# LotteryHistoryQuery 项目交接文档（Handoff）

> 用途：新账号 / 新实例接手本项目的完整指引。本文件为**脱敏版**，不含任何明文密钥。
> 敏感信息（GitHub Token、Webhook Secret）由项目持有者另行私下提供。

## 1. 项目概览

彩票历史查询系统，共三端：

| 端 | 目录 | 说明 |
|----|------|------|
| 用户端 App | `app/` | Android，选号/查询/开奖/历史/最新/客服 |
| 管理端 App | `admin-app/` | Android，用户/审计/仪表盘/设置 |
| Web 端 | `server/` | Node.js(Express) 后端 + Web 管理面板 + 用户端网页版 |

- 仓库：`https://github.com/yimu190097/LotteryHistoryQuery`
- 生产：VM 上 systemd 服务 `lottery-server`（root），项目目录 `/root/lottery`
- 数据源：`http://data.17500.cn/{code}_desc.txt`，code ∈ `ssq / dlt2 / 3d / pl3 / pl5 / 7xc / kl8 / 7lc`

## 2. 关键配置

| 项 | 值 |
|----|-----|
| 管理后台 | `https://showbiz-unbridle-decent.ngrok-free.dev` |
| 用户端网页版 | `https://showbiz-unbridle-decent.ngrok-free.dev/web/index.html` |
| 部署 Webhook | `https://showbiz-unbridle-decent.ngrok-free.dev/api/deploy/webhook` |
| Webhook Secret | （私密，向持有者索要） |
| GitHub Token | （私密，向持有者索要或自建） |
| 管理员账号 | `admin / admin123`（首次登录强制改密，见 database.js） |
| 健康检查 | `GET /api/health` |
| 服务器部署日志 | VM 上 `/var/log/lottery-update.log` |

## 3. 部署流程（唯一正确姿势）

**禁止在 VM 上手动 `git pull`**（GitHub 直连超时）。

1. 改代码（以 GitHub 云端为准）。
2. 推送改动（有 git 用 `git push`；无 git 用 GitHub Contents API，`content` 字段需 base64，`sha` 需为当前远端值否则 409）。
3. 触发 webhook 部署：

```bash
curl -s -X POST https://showbiz-unbridle-decent.ngrok-free.dev/api/deploy/webhook \
  -H "X-Webhook-Secret: <私密>" \
  -H "Content-Type: application/json"
```

- 部署脚本 `deploy/update.sh` 走国内镜像源（ghfast.top / gh-proxy.com / raw.gitmirror.com / raw 直连），两阶段：先升级 `deploy.js` 解除死锁，再并行下载全量代码 + `npm install` + 一次性重启。
- `deploy.js` 超时 900s；部署 30–120 秒生效。

## 4. 已完成（云端已上线）

1. 用户端网页版 `server/public/web/index.html`：360dp 像素级还原，8 彩种选号/查询/结果/最新/历史，多规则版本匹配（双色球 2026 新规「福运奖」、大乐透 `dlt2` 等）。
2. 网页版「下载APP」入口：`downloadUserApk()` 从 `/api/apk-list` 取用户端 APK。
3. 数据源代理 `GET /api/lottery/:code`，`LOTTERY_CODES = ['ssq','dlt2','3d','pl3','pl5','7xc','kl8','7lc']`。
4. 管理端 APK 页含「用户端网页版」入口（`server/public/js/admin.js`）。
5. GitHub Release APK 同步：`POST /api/apk/sync`、`GET /api/apk/sync-status`、`GET /api/apk-list`、`POST /api/upload-apk`。
6. 后端路由骨架完整：`auth/users/stats/config/chat/deploy` + `ws/chatServer` + `middleware/auth`。
7. 数据库表：`admins/users/quotas/pending_sync/audit_log/system_config/chat_sessions/chat_messages`，默认 free_quota=10、query_price=1。

## 5. 未完成（接手后继续）

### 5.1【核心】用户端网页版真实登录 + 配额兑扣
- 现状：`server/public/web/index.html` 的 `submitAuth()` 是前端假登录（仅脱敏 + toast + 硬编码「月租用户·剩余30天」），未调后端、无真实鉴权、无查询扣减。
- 做法：接 `POST /api/users/client/login` / `register`，token 存 localStorage；查询时按 `system_config` 的 `free_quota/query_price` 扣减 `quotas.remaining_queries`；`quotaInfo` 显示真实剩余次数。

### 5.2 条件性奖级边界（可选）
- 福运奖开关、大乐透奖池上浮等，网页版 `matchAll` 已用 `vers`+`r[4]` 预留，需与 App `LotteryMatcher` 边界对齐。

### 5.3 本地↔云端同步（接手前先做）
- 本地工作目录若缺失 `ws/`、`middleware/`、`routes/{auth,users,stats,chat}.js`、`utils/`、`public/js/{call,chat}.js` 等，**以 GitHub 云端为准**，切勿把「本地没有」误当「云端没有」。

### 5.4 可选功能对齐（App 有、网页版无）
- 客服聊天（CustomerServiceActivity）、语音通话（WebRtcClient/VoiceCallActivity）。

## 6. 关键坑

1. 本地目录与云端不同步，务必以云端为准。
2. VM 连 GitHub 直连/`codeload.github.com` 超时，必须走 `raw.githubusercontent.com` 或国内镜像。
3. `koa-connect` 包 Express 中间件会漏 ctx，用原生 Express 中间件。
4. `update.sh` 中途 `systemctl restart` 会杀掉部署进程自身（同 cgroup），重启放到最后一次性执行。
5. 数据源 code 曾错用 `qlc/qx/dlt`，正确为 `7lc/7xc/dlt2`。

## 7. 接口速查

| 接口 | 说明 |
|------|------|
| `GET /api/health` | 健康检查 |
| `GET /api/lottery/:code` | 开奖历史代理 |
| `POST /api/apk/sync` | 从 GitHub Release 同步 APK（管理员） |
| `GET /api/apk/sync-status` | APK 同步进度 |
| `GET /api/apk-list` | APK 列表 |
| `POST /api/upload-apk` | 上传 APK（管理员） |
| `POST /api/auth/login` | 管理员登录 |
| `/api/users/client/*` | 用户端登录/注册（限流） |
| `/api/config` `/api/stats` `/api/chat` `/api/deploy` | 配置/统计/客服/部署 |

## 8. 快速验证

```bash
curl -s https://showbiz-unbridle-decent.ngrok-free.dev/api/health
curl -s https://showbiz-unbridle-decent.ngrok-free.dev/api/lottery/7xc | head
curl -s https://showbiz-unbridle-decent.ngrok-free.dev/web/index.html | grep -c downloadUserApk
```

## 9. 交接须知

- 本仓库公开，**禁止提交任何 token / secret**。
- 新账号 push 代码需本仓库写权限（被添加为 collaborator，或使用持有者提供的 token）。
- GitHub Token、Webhook Secret 由持有者线下提供；拿到后用于 Contents API 推送与 webhook 触发。