#!/usr/bin/env bash
# ============================================================================
# 数据库自动备份脚本（每日凌晨 3 点执行）
# 保留最近 7 天的备份
# ============================================================================
set -euo pipefail

BACKUP_DIR="/root/lottery/backups"
DB_PATH="/root/lottery/server/data/admin.db"
MAX_BACKUPS=7
LOG_FILE="/var/log/lottery-backup.log"

mkdir -p "$BACKUP_DIR"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

if [[ ! -f "$DB_PATH" ]]; then
    log "数据库文件不存在: $DB_PATH，跳过备份"
    exit 0
fi

TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
BACKUP_FILE="$BACKUP_DIR/admin_${TIMESTAMP}.db"

log "开始备份: $DB_PATH → $BACKUP_FILE"

# 使用 sqlite3 .backup 命令（如果可用）或直接 cp
if command -v sqlite3 &>/dev/null; then
    sqlite3 "$DB_PATH" ".backup '$BACKUP_FILE'"
else
    cp "$DB_PATH" "$BACKUP_FILE"
fi

# 校验备份文件大小
ORIG_SIZE=$(stat -c%s "$DB_PATH" 2>/dev/null || echo 0)
BACKUP_SIZE=$(stat -c%s "$BACKUP_FILE" 2>/dev/null || echo 0)
if [[ "$BACKUP_SIZE" -lt 100 ]]; then
    log "备份失败：备份文件太小（${BACKUP_SIZE} bytes）"
    rm -f "$BACKUP_FILE"
    exit 1
fi

log "备份成功（${BACKUP_SIZE} bytes）"

# 清理 7 天前的旧备份
OLD_COUNT=$(find "$BACKUP_DIR" -name "admin_*.db" -mtime +$MAX_BACKUPS | wc -l)
if [[ "$OLD_COUNT" -gt 0 ]]; then
    find "$BACKUP_DIR" -name "admin_*.db" -mtime +$MAX_BACKUPS -delete
    log "清理了 ${OLD_COUNT} 个旧备份"
fi

log "备份完成"