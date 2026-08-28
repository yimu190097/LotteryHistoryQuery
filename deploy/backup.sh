#!/usr/bin/env bash
# ============================================================================
# 数据库自动备份脚本（每日凌晨 3 点执行）
# 保留最近 7 天的本地备份 + 同步到 Cloudflare R2
# ============================================================================
set -euo pipefail

BACKUP_DIR="/root/lottery/backups"
DB_PATH="/root/lottery/server/data/admin.db"
MAX_BACKUPS=7
LOG_FILE="/var/log/lottery-backup.log"

# Cloudflare R2 配置（从 .env 读取，未配置则跳过远程同步）
ENV_FILE="/root/lottery/server/.env"
R2_BUCKET=""
R2_ENDPOINT=""
R2_ACCESS_KEY=""
R2_SECRET_KEY=""

if [[ -f "$ENV_FILE" ]]; then
    source <(grep -E '^(R2_BUCKET|R2_ENDPOINT|R2_ACCESS_KEY|R2_SECRET_KEY)=' "$ENV_FILE" || true)
fi

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
BACKUP_TAR="$BACKUP_DIR/admin_${TIMESTAMP}.tar.gz"

log "开始备份: $DB_PATH → $BACKUP_FILE"

# 使用 sqlite3 .backup 命令（如果可用）或直接 cp
if command -v sqlite3 &>/dev/null; then
    sqlite3 "$DB_PATH" ".backup '$BACKUP_FILE'"
else
    cp "$DB_PATH" "$BACKUP_FILE"
fi

# 校验备份文件大小
BACKUP_SIZE=$(stat -c%s "$BACKUP_FILE" 2>/dev/null || echo 0)
if [[ "$BACKUP_SIZE" -lt 100 ]]; then
    log "备份失败：备份文件太小（${BACKUP_SIZE} bytes）"
    rm -f "$BACKUP_FILE"
    exit 1
fi

log "本地备份成功（${BACKUP_SIZE} bytes）"

# 清理 7 天前的旧备份
OLD_COUNT=$(find "$BACKUP_DIR" -name "admin_*.db" -mtime +$MAX_BACKUPS | wc -l)
if [[ "$OLD_COUNT" -gt 0 ]]; then
    find "$BACKUP_DIR" -name "admin_*.db" -mtime +$MAX_BACKUPS -delete
    find "$BACKUP_DIR" -name "admin_*.tar.gz" -mtime +$MAX_BACKUPS -delete 2>/dev/null || true
    log "清理了 ${OLD_COUNT} 个旧备份"
fi

# ========== Cloudflare R2 远程同步 ==========
if [[ -n "$R2_BUCKET" && -n "$R2_ENDPOINT" && -n "$R2_ACCESS_KEY" && -n "$R2_SECRET_KEY" ]]; then
    log "开始同步到 Cloudflare R2: $R2_BUCKET"

    # 压缩备份文件（节省存储空间）
    gzip -c "$BACKUP_FILE" > "$BACKUP_TAR"
    COMPRESSED_SIZE=$(stat -c%s "$BACKUP_TAR" 2>/dev/null || echo 0)
    log "压缩后大小: ${COMPRESSED_SIZE} bytes"

    if command -v aws &>/dev/null; then
        # 使用 awscli（S3 兼容）
        AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY" \
        AWS_SECRET_ACCESS_KEY="$R2_SECRET_KEY" \
        aws s3 cp "$BACKUP_TAR" "s3://${R2_BUCKET}/backups/$(basename "$BACKUP_TAR")" \
            --endpoint-url "https://${R2_ENDPOINT}" \
            --region auto \
            --no-verify-ssl 2>&1 | tee -a "$LOG_FILE"

        # 清理 R2 上 7 天前的旧备份
        AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY" \
        AWS_SECRET_ACCESS_KEY="$R2_SECRET_KEY" \
        aws s3 ls "s3://${R2_BUCKET}/backups/" \
            --endpoint-url "https://${R2_ENDPOINT}" \
            --region auto 2>/dev/null | while read -r _ _ _ _ obj; do
            [[ -z "$obj" ]] && continue
            obj_date=$(echo "$obj" | grep -oP 'admin_\d{8}' | head -1 || true)
            if [[ -n "$obj_date" ]]; then
                obj_epoch=$(date -d "${obj_date:6:4}-${obj_date:10:2}-${obj_date:12:2}" +%s 2>/dev/null || echo 0)
                cutoff=$(date -d "$MAX_BACKUPS days ago" +%s 2>/dev/null || echo 0)
                if [[ "$obj_epoch" -lt "$cutoff" ]]; then
                    AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY" \
                    AWS_SECRET_ACCESS_KEY="$R2_SECRET_KEY" \
                    aws s3 rm "s3://${R2_BUCKET}/backups/$obj" \
                        --endpoint-url "https://${R2_ENDPOINT}" \
                        --region auto 2>/dev/null && log "R2 清理旧备份: $obj"
                fi
            fi
        done

        log "R2 同步完成"
    else
        log "警告：未安装 awscli，跳过 R2 同步（apt install awscli）"
    fi
else
    log "R2 未配置，跳过远程同步"
fi

log "备份完成"