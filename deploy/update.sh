#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本（由 webhook 触发）
# ============================================================================
set -euo pipefail

PROJECT_DIR="/root/lottery"
LOG_FILE="/var/log/lottery-update.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

log "========== 开始自动更新 =========="

cd "$PROJECT_DIR"

log "步骤 1/3: git pull"
git pull origin main 2>&1 | tee -a "$LOG_FILE"

log "步骤 2/3: npm install"
cd "$PROJECT_DIR/server"
npm install --omit=dev 2>&1 | tee -a "$LOG_FILE"

log "步骤 3/3: 重启 lottery-server"
systemctl restart lottery-server 2>&1 | tee -a "$LOG_FILE"

sleep 2

if curl -s http://localhost:3000/api/health | grep -q '"ok"'; then
    log "更新成功！服务已重启"
else
    log "警告：服务重启后健康检查失败，请查看 journalctl -u lottery-server -n 30"
fi

log "========== 更新完成 =========="