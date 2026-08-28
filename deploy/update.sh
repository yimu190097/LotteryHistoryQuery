#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本（由 webhook 触发）
# 优先使用 codeload.github.com 下载 zip，失败则回退到 git pull
# ============================================================================
set -euo pipefail
PROJECT_DIR="/root/lottery"
LOG_FILE="/var/log/lottery-update.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "========== 开始自动更新 =========="

# 步骤 1: 下载最新代码
log "步骤 1/3: 下载最新代码"

# 方案 A: 从 codeload.github.com 下载 zip（单次 HTTP 请求）
ZIP_URL="https://codeload.github.com/yimu190097/LotteryHistoryQuery/zip/refs/heads/main"
TMP_ZIP="/tmp/lottery-main.zip"
TMP_DIR="/tmp/lottery-update-$$"

if curl -fsSL --connect-timeout 30 --max-time 120 "$ZIP_URL" -o "$TMP_ZIP" 2>&1 | tee -a "$LOG_FILE"; then
    log "zip 下载成功，解压中..."
    mkdir -p "$TMP_DIR"
    unzip -o "$TMP_ZIP" -d "$TMP_DIR" 2>&1 | tee -a "$LOG_FILE"
    EXTRACTED=$(find "$TMP_DIR" -maxdepth 1 -name "LotteryHistoryQuery-*" -type d | head -1)
    if [ -n "$EXTRACTED" ]; then
        rsync -a --exclude='server/data' --exclude='server/node_modules' --exclude='server/.env' \
              "$EXTRACTED/" "$PROJECT_DIR/" 2>&1 | tee -a "$LOG_FILE"
        log "文件同步完成"
    else
        log "错误：未找到解压目录"
        exit 1
    fi
    rm -rf "$TMP_ZIP" "$TMP_DIR"
else
    log "zip 下载失败，尝试方案 B: git pull"
    cd "$PROJECT_DIR"
    if git pull origin main --depth 1 2>&1 | tee -a "$LOG_FILE"; then
        log "git pull 成功"
    else
        log "git pull 也失败，尝试方案 C: raw.githubusercontent.com 逐文件下载"
        RAW_BASE="https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
        for f in \
            server/package.json \
            server/package-lock.json \
            server/src/index.js \
            server/src/db/database.js \
            server/src/routes/auth.js \
            server/src/routes/config.js \
            server/src/routes/users.js \
            server/src/routes/deploy.js \
            server/src/routes/chat.js \
            server/src/middleware/auth.js \
            server/public/index.html \
            server/public/css/style.css \
            server/public/js/admin.js \
            server/public/js/chat.js \
            server/public/js/call.js; do
            dest="$PROJECT_DIR/$f"
            mkdir -p "$(dirname "$dest")"
            if curl -fsSL --connect-timeout 15 "$RAW_BASE/$f" -o "$dest" 2>/dev/null; then
                log "  已下载: $f"
            else
                log "  跳过: $f"
            fi
        done
    fi
fi

# 步骤 2: 安装依赖
log "步骤 2/3: npm install"
cd "$PROJECT_DIR/server"
npm install --omit=dev 2>&1 | tee -a "$LOG_FILE"

# 步骤 3: 重启服务
log "步骤 3/3: 重启 lottery-server"
systemctl restart lottery-server 2>&1 | tee -a "$LOG_FILE"
sleep 2

if curl -s http://localhost:3000/api/health | grep -q '"ok"'; then
    log "更新成功！服务已重启"
else
    log "警告：服务重启后健康检查失败，请查看 journalctl -u lottery-server -n 30"
fi

log "========== 更新完成 =========="