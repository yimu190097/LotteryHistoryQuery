#!/usr/bin/env bash
# ============================================================================
# 彩票服务器 - 自动更新脚本（两阶段）
# 阶段A：优先下载新版 deploy.js 并立即重启，让服务加载最长超时的新版部署入口
# 阶段B：下载其余代码 + npm install + 重启；APK 后台独立进程同步
# ============================================================================
# P0-5: 启用 set -e —— 任意未显式容错的失败立即终止脚本，
# 避免半完成状态被误判为「部署成功」。预期失败的命令显式用 || true / || log 容错。
set -euo pipefail
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
    local tmp="$dest.tmp"
    mkdir -p "$(dirname "$dest")"
    for base in "${RAW_BASES[@]}"; do
        # 先下到临时文件再原子替换，避免下载中断留下半损坏的线上文件（尤其 deploy.js）
        if curl -fsSL --connect-timeout 8 --max-time 20 "$base/$rel" -o "$tmp" 2>/dev/null; then
            mv -f "$tmp" "$dest"
            log "  OK: $rel"
            return 0
        fi
    done
    rm -f "$tmp"
    log "  SKIP: $rel"
    return 1
}

# ============================================================================
# 阶段 A：先升级部署入口 deploy.js（这里不做 restart）
# 注意：中途 systemctl restart 会连带杀掉部署进程自身(同 cgroup)，导致后续
# 阶段永远不执行。因此阶段A仅下载 upgrade，重启统一放到阶段B末尾一次性执行。
# ============================================================================
if [ "${STAGE_A_DONE:-0}" != "1" ]; then
  log "阶段 A: 升级部署入口 deploy.js"
  # download_file 失败不应终止整个部署（后续阶段 B 会再次下载）
  STAGE_A_DONE=1 download_file "server/src/routes/deploy.js" || log "  阶段 A: deploy.js 下载失败，继续后续步骤"
  log "阶段 A 完成（暂不重启，继续下载其余代码）"
fi

# ============================================================================
# 若为后台 APK 同步模式，则只同步 APK 后退出
# ============================================================================
APK_DIR="$PROJECT_DIR/server/public/downloads"
sync_apk() {
    mkdir -p "$APK_DIR"
    local RELEASE_TAG=""
    local LATEST_REL=""
    for ghbase in \
      "https://ghfast.top/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
      "https://gh-proxy.com/https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest" \
      "https://api.github.com/repos/yimu190097/LotteryHistoryQuery/releases/latest"; do
      if LATEST_REL=$(curl -fsSL --connect-timeout 8 --max-time 15 "$ghbase" 2>/dev/null); then break; fi
    done
    if [ -z "$LATEST_REL" ]; then
      log "  APK: 获取最新 Release 失败（稍后重试）"
      return 1
    fi
    RELEASE_TAG=$(echo "$LATEST_REL" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p')
    [ -n "$RELEASE_TAG" ] || RELEASE_TAG="v24.2"

    # 动态提取 Release 中所有 .apk 资产名（Actions 用动态标签命名 cmd-日期-序号，
    # 不能用旧硬编码文件名 LotteryHistoryQuery_v24.2.apk，否则永远下载不到）
    local apks=()
    while IFS= read -r name; do
      [ -n "$name" ] && apks+=("$name")
    done < <(echo "$LATEST_REL" | grep -oE '"name":"[^"]+\.apk"' | sed 's/"name":"//; s/"$//')

    if [ "${#apks[@]}" -eq 0 ]; then
      log "  APK: Release 中没有找到 .apk 资产"
      return 1
    fi

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
            mv -f "$APK_DIR/$apk.tmp" "$APK_DIR/$apk" || { log "  APK 移动失败: $apk"; rm -f "$APK_DIR/$apk.tmp"; continue 2; }
            log "  APK OK: $apk ($(($size/1024/1024)) MB)"
            ok=1
            break
          fi
          rm -f "$APK_DIR/$apk.tmp"
        fi
      done
      [ "$ok" -eq 0 ] && log "  APK SKIP: $apk 下载失败（下次部署将重试）"
    done

    # 清理旧版本：每个 APK 前缀仅保留最新一份（动态标签命名会无限累积）。
    # 排序规则：cmd- 动态命名永远视为新版本；旧固定命名(v24.2 等)视为最旧，避免被误判为最新。
    local prefix=""
    for prefix in LotteryHistoryQuery LotteryAdmin; do
      local keep=""
      keep=$(ls -1 "$APK_DIR"/${prefix}_*.apk 2>/dev/null \
        | awk '{ r = ($0 ~ /cmd-/) ? "1_" $0 : "0_" $0; print r }' \
        | sort | tail -n1 | sed 's/^[01]_//')
      [ -n "$keep" ] || continue
      local f=""
      for f in "$APK_DIR"/${prefix}_*.apk; do
        [ -f "$f" ] || continue
        [ "$f" = "$keep" ] && continue
        rm -f "$f"
        log "  APK 清理旧版本: $(basename "$f")"
      done
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
  deploy/deploy.sh
  deploy/build-apk.sh
  deploy/health-check.sh
  deploy/backup.sh
  server/package.json
  server/package-lock.json
  server/src/index.js
  server/src/db/database.js
  server/src/utils/init.js
  server/src/middleware/auth.js
  server/src/routes/auth.js
  server/src/routes/config.js
  server/src/routes/users.js
  server/src/routes/chat.js
  server/src/routes/stats.js
  server/src/routes/deploy.js
  server/src/ws/chatServer.js
  server/src/ws/callManager.js
  server/public/index.html
  server/public/css/admin.css
  server/public/js/admin.js
  server/public/web/index.html
  server/public/js/chat.js
  server/public/js/call.js
)

PIDS=()
for f in "${FILES[@]}"; do
    download_file "$f" &
    PIDS+=($!)
done
for pid in "${PIDS[@]}"; do
    # 单文件下载失败不应阻断整个部署（download_file 内部已 log SKIP）
    wait "$pid" || true
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