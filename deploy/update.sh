#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本
# 并行下载代码（国内镜像多源）+ 增量同步 APK，避免 VM 直连 GitHub 超时
# ============================================================================
set -uo pipefail
PROJECT_DIR="/root/lottery"
LOG_FILE="/var/log/lottery-update.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "========== 开始自动更新 =========="

# ============================================================================
# 步骤 0: APK 自动同步（增量：已存在且非空则跳过；带国内镜像多源重试）
# ============================================================================
log "步骤 0/4: 自动同步 APK"
APK_DIR="$PROJECT_DIR/server/public/downloads"
mkdir -p "$APK_DIR"

# 探测最新 release tag
RELEASE_TAG="v24.2"
LATEST_REL=""
for ghbase in \
  "https://ghfast.top/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
  "https://gh-proxy.com/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
  "https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest"; do
  if LATEST_REL=$(curl -fsSL --connect-timeout 8 --max-time 15 "$ghbase" 2>/dev/null); then
    break
  fi
done
if [ -n "$LATEST_REL" ]; then
  RELEASE_TAG=$(echo "$LATEST_REL" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p')
  [ -n "$RELEASE_TAG" ] || RELEASE_TAG="v24.2"
fi
log "  Release tag: $RELEASE_TAG"

APKS=("LotteryAdmin_v1.0.apk" "LotteryHistoryQuery_v24.2.apk")
for apk in "${APKS[@]}"; do
  # 增量：已存在且非空直接跳过
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

# ============================================================================
# 步骤 1: 下载最新代码（并行多源）
# ============================================================================
log "步骤 1/4: 下载最新代码（并行）"

RAW_BASES=(
  "https://ghfast.top/https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://gh-proxy.com/https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://raw.gitmirror.com/yimu190097/LotteryHistoryQuery/main"
  "https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
)

FILES=(
  deploy/update.sh
  server/package.json
  server/package-lock.json
  server/src/index.js
  server/src/db/database.js
  server/src/routes/auth.js
  server/src/routes/config.js
  server/src/routes/users.js
  server/src/routes/deploy.js
  server/src/routes/chat.js
  server/src/middleware/auth.js
  server/public/index.html
  server/public/css/style.css
  server/public/js/admin.js
  server/public/js/chat.js
  server/public/js/call.js
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

# 并行下载：每个文件后台跑，等全部结束（比串行快一个数量级）
PIDS=()
for f in "${FILES[@]}"; do
    download_file "$f" &
    PIDS+=($!)
done
for pid in "${PIDS[@]}"; do
    wait "$pid"
done

# ============================================================================
# 步骤 2: npm install
# ============================================================================
log "步骤 2/4: npm install"
cd "$PROJECT_DIR/server"
npm install --omit=dev 2>&1 | tee -a "$LOG_FILE" || log "npm install 失败"

# ============================================================================
# 步骤 3: 重启服务
# ============================================================================
log "步骤 3/4: 重启 lottery-server"
systemctl restart lottery-server 2>&1 | tee -a "$LOG_FILE" || log "重启失败"
sleep 3

# ============================================================================
# 步骤 4: 健康检查
# ============================================================================
log "步骤 4/4: 健康检查"
if curl -s http://localhost:3000/api/health | grep -q '"ok"'; then
    log "更新成功！服务已重启"
else
    log "警告：健康检查失败，查看 journalctl -u lottery-server -n 30"
fi

log "========== 更新完成 =========="