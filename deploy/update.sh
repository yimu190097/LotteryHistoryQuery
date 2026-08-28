#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本（两阶段）
# 阶段A：优先下载新版 deploy.js 并立即重启，让服务加载最长超时的新版部署入口
# 阶段B：下载其余代码 + npm install + 重启；APK 后台独立进程同步
# ============================================================================
set -uo pipefail
PROJECT_DIR="/root/lottery"
LOG_FILE="/var/log/lottery-update.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "========== 开始自动更新 =========="

RAW_BASES=(
  "https://ghfast.top/https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://gh-proxy.com/https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://raw.gitmirror.com/yimu190097/LotteryHistoryQuery/main"
  "https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
)

download_file() {
    local rel="$1"
    local dest="$PROJECT_DIR/$rel"
    mkdir -p "$(dirname "$dest")"
    for base in "${RAW_BASES[@]}"; do
        if curl -fsSL --connect-timeout 8 --max-time 20 "$base/$rel" -o "$dest" 2>/dev/null; then
            log "  OK: $rel"
            return 0
        fi
    done
    log "  SKIP: $rel"
    return 1
}

# ============================================================================
# 阶段 A：先保证部署入口升级（快速，1 个文件 + 立即重启）
# 目的：把服务切换到 exec 超时=900s 的新版 deploy.js，确保大任务不被砍
# ============================================================================
if [ "${STAGE_A_DONE:-0}" != "1" ]; then
  log "阶段 A: 升级部署入口 deploy.js 并重启"
  STAGE_A_DONE=1 download_file "server/src/routes/deploy.js"
  systemctl restart lottery-server 2>&1 | tee -a "$LOG_FILE" || log "阶段A重启失败"
  sleep 3
  log "阶段 A 完成，服务已切换到新版部署入口"
fi

# ============================================================================
# 若为后台 APK 同步模式，则只同步 APK 后退出
# ============================================================================
APK_DIR="$PROJECT_DIR/server/public/downloads"
sync_apk() {
    mkdir -p "$APK_DIR"
    local RELEASE_TAG="v24.2"
    local LATEST_REL=""
    for ghbase in \
      "https://ghfast.top/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
      "https://gh-proxy.com/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
      "https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest"; do
      if LATEST_REL=$(curl -fsSL --connect-timeout 8 --max-time 15 "$ghbase" 2>/dev/null); then break; fi
    done
    if [ -n "$LATEST_REL" ]; then
      RELEASE_TAG=$(echo "$LATEST_REL" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p')
      [ -n "$RELEASE_TAG" ] || RELEASE_TAG="v24.2"
    fi
    local apks=("LotteryAdmin_v1.0.apk" "LotteryHistoryQuery_v24.2.apk")
    local ok=0 size=0 apk=""
    for apk in "${apks[@]}"; do
      if [ -s "$APK_DIR/$apk" ]; then
        size=$(stat -c%s "$APK_DIR/$apk" 2>/dev/null || echo 0)
        log "  APK 已存在: $apk ($(($size/1024/1024)) MB)"
        continue
      fi
      ok=0
      for base in \
        "https://ghfast.top/https://github.com/yimu190097/LotteryHistoryQuery/releases/download/$RELEASE_TAG" \
        "https://gh-proxy.com/https://github.com/yimu190097/LotteryHistoryQuery/releases/download/$RELEASE_TAG" \
        "https://github.com/yimu190097/LotteryHistoryQuery/releases/download/$RELEASE_TAG"; do
        if curl -fsSL --connect-timeout 8 --max-time 40 "$base/$apk" -o "$APK_DIR/$apk.tmp" 2>/dev/null; then
          size=$(stat -c%s "$APK_DIR/$apk.tmp" 2>/dev/null || echo 0)
          if [ "$size" -gt 1000000 ]; then
            mv -f "$APK_DIR/$apk.tmp" "$APK_DIR/$apk"
            log "  APK OK: $apk ($(($size/1024/1024)) MB)"
            ok=1
            break
          fi
          rm -f "$APK_DIR/$apk.tmp"
        fi
      done
      [ "$ok" -eq 0 ] && log "  APK SKIP: $apk 下载失败（下次部署将重试）"
    done
}

if [ "${LEAN_APK:-0}" = "1" ]; then
  log "后台模式：仅同步 APK"
  sync_apk
  exit 0
fi

log "步骤启动 APK 后台同步（不占用主流程）"
if [ "${APK_BG_STARTED:-0}" != "1" ]; then
  APK_BG_STARTED=1 LEAN_APK=1 nohup bash "$PROJECT_DIR/deploy/update.sh" >/dev/null 2>&1 &
  disown || true
fi

# ============================================================================
# 阶段 B：下载其余代码（并行）
# ============================================================================
log "阶段 B: 下载其余代码（并行）"
FILES=(
  deploy/update.sh
  server/package.json
  server/package-lock.json
  server/src/index.js
  server/src/db/database.js
  server/src/routes/auth.js
  server/src/routes/config.js
  server/src/routes/users.js
  server/src/routes/chat.js
  server/src/routes/deploy.js
  server/src/middleware/auth.js
  server/public/index.html
  server/public/css/style.css
  server/public/js/admin.js
  server/public/js/chat.js
  server/public/js/call.js
)

PIDS=()
for f in "${FILES[@]}"; do
    download_file "$f" &
    PIDS+=($!)
done
for pid in "${PIDS[@]}"; do
    wait "$pid"
done

# ============================================================================
# npm install
# ============================================================================
log "阶段 B: npm install"
cd "$PROJECT_DIR/server"
npm install --omit=dev 2>&1 | tee -a "$LOG_FILE" || log "npm install 失败"

# ============================================================================
# 重启服务，加载全部新代码
# ============================================================================
log "阶段 B: 重启 lottery-server"
systemctl restart lottery-server 2>&1 | tee -a "$LOG_FILE" || log "重启失败"
sleep 3

log "阶段 B: 健康检查"
if curl -s http://localhost:3000/api/health | grep -q '"ok"'; then
    log "更新成功！服务已重启"
else
    log "警告：健康检查失败，查看 journalctl -u lottery-server -n 30"
fi

log "========== 更新完成 =========="