#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本
# 优先 raw.githubusercontent.com（VM 可访问），其次 codeload，最后 git pull
# ============================================================================
set -euo pipefail
PROJECT_DIR="/root/lottery"
LOG_FILE="/var/log/lottery-update.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "========== 开始自动更新 =========="
log "步骤 1/3: 下载最新代码"

RAW_BASE="https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main"
SUCCESS=0

# 方案 A: raw.githubusercontent.com（VM 可直接访问，最可靠）
log "方案 A: raw.githubusercontent.com 逐文件下载..."
download_file() {
    local rel="$1"
    local dest="$PROJECT_DIR/$rel"
    mkdir -p "$(dirname "$dest")"
    if curl -fsSL --connect-timeout 10 --max-time 30 "$RAW_BASE/$rel" -o "$dest" 2>/dev/null; then
        return 0
    else
        return 1
    fi
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