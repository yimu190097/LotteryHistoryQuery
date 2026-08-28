#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本
# 优先 raw.githubusercontent.com + 国内镜像，其次 codeload，最后 git pull
# ============================================================================
set -euo pipefail
PROJECT_DIR="/root/lottery"
LOG_FILE="/var/log/lottery-update.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "========== 开始自动更新 =========="

# ============================================================================
# APK 自动同步：从 GitHub Release（带国内镜像）拉取两个 APK 到 downloads 目录
# 无需登录、随部署自动完成，避免残留 0 字节空文件
# ============================================================================
log "步骤 0/4: 自动同步 APK"
APK_DIR="$PROJECT_DIR/server/public/downloads"
mkdir -p "$APK_DIR"
RELEASE_TAG="v24.2"
LATEST_REL=""
for ghbase in \
  "https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
  "https://ghfast.top/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
  "https://gh-proxy.com/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest"; do
  if LATEST_REL=$(curl -fsSL --connect-timeout 10 --max-time 20 "$ghbase" 2>/dev/null); then
    break
  fi
done
# 若没拿到 latest，退回固定 v24.2；否则取最新 tag
if [ -n "$LATEST_REL" ]; then
  RELEASE_TAG=$(echo "$LATEST_REL" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p')
fi
APKS=("LotteryAdmin_v1.0.apk" "LotteryHistoryQuery_v24.2.apk")
for apk in "${APKS[@]}"; do
  ok=0
  for base in \
    "https://github.com/yimu190097/LotteryHistoryQuery/releases/download/$RELEASE_TAG" \
    "https://ghfast.top/https://github.com/yimu190097/LotteryHistoryQuery/releases/download/$RELEASE_TAG" \
    "https://gh-proxy.com/https://github.com/yimu190097/LotteryHistoryQuery/releases/download/$RELEASE_TAG"; do
    if curl -fsSL --connect-timeout 10 --max-time 60 "$base/$apk" -o "$APK_DIR/$apk.tmp" 2>/dev/null; then
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
  [ "$ok" -eq 0 ] && log "  APK SKIP: $apk 下载失败"
done

log "步骤 1/4: 下载最新代码"

# 依次尝试的下载基地址（国内网络镜像，解决 VM 直连 GitHub 超时）
RAW_BASES=(
  "https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://ghfast.top/https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://gh-proxy.com/https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
  "https://raw.gitmirror.com/yimu190097/LotteryHistoryQuery/main"
)
SUCCESS=0

# 方案 A: 镜像列表逐个尝试，只要有一个源成功下载该文件即 OK
log "方案 A: 镜像列表逐文件下载..."
download_file() {
    local rel="$1"
    local dest="$PROJECT_DIR/$rel"
    mkdir -p "$(dirname "$dest")"
    for base in "${RAW_BASES[@]}"; do
        if curl -fsSL --connect-timeout 10 --max-time 30 "$base/$rel" -o "$dest" 2>/dev/null; then
            return 0
        fi
    done
    return 1
}

FAILED_FILES=0
for f in deploy/update.sh server/package.json server/package-lock.json server/src/index.js server/src/db/database.js server/src/routes/auth.js server/src/routes/config.js server/src/routes/users.js server/src/routes/deploy.js server/src/routes/chat.js server/src/middleware/auth.js server/public/index.html server/public/css/style.css server/public/js/admin.js server/public/js/chat.js server/public/js/call.js; do
    if download_file "$f"; then
        log "  OK: $f"
    else
        log "  SKIP: $f"
        FAILED_FILES=$((FAILED_FILES + 1))
    fi
done

if [ "$FAILED_FILES" -eq 0 ]; then
    SUCCESS=1
    log "方案 A: 全部文件下载成功"
else
    log "方案 A: $FAILED_FILES 个文件跳过，尝试方案 B: codeload zip..."
    ZIP_URL="https://codeload.github.com/yimu190097/LotteryHistoryQuery/zip/refs/heads/main"
    TMP_ZIP="/tmp/lottery-main.zip"
    TMP_DIR="/tmp/lottery-update-$$"
    if curl -fsSL --connect-timeout 15 --max-time 60 "$ZIP_URL" -o "$TMP_ZIP" 2>/dev/null; then
        log "zip 下载成功，解压中..."
        mkdir -p "$TMP_DIR"
        if unzip -o "$TMP_ZIP" -d "$TMP_DIR" 2>/dev/null; then
            EXTRACTED=$(find "$TMP_DIR" -maxdepth 1 -name "LotteryHistoryQuery-*" -type d | head -1)
            if [ -n "$EXTRACTED" ]; then
                rsync -a --exclude='server/data' --exclude='server/node_modules' --exclude='server/.env' "$EXTRACTED/" "$PROJECT_DIR/" 2>/dev/null
                log "方案 B: 文件同步完成"
                SUCCESS=1
            fi
        fi
        rm -rf "$TMP_ZIP" "$TMP_DIR"
    fi
    if [ "$SUCCESS" -eq 0 ]; then
        log "方案 B 失败，尝试方案 C: git pull..."
        cd "$PROJECT_DIR"
        if git pull origin main --depth 1 2>/dev/null; then
            log "方案 C: git pull 成功"
            SUCCESS=1
        else
            log "方案 C: git pull 也失败了"
        fi
    fi
fi

if [ "$SUCCESS" -eq 0 ]; then
    log "警告：所有下载方案均失败！"
fi

log "步骤 2/3: npm install"
cd "$PROJECT_DIR/server"
npm install --omit=dev 2>&1 | tee -a "$LOG_FILE" || log "npm install 失败"

log "步骤 3/3: 重启 lottery-server"
systemctl restart lottery-server 2>&1 | tee -a "$LOG_FILE" || log "重启失败"
sleep 2

if curl -s http://localhost:3000/api/health | grep -q '"ok"'; then
    log "更新成功！服务已重启"
else
    log "警告：健康检查失败，查看 journalctl -u lottery-server -n 30"
fi

log "========== 更新完成 =========="