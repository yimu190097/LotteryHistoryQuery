#!/usr/bin/env bash
# ============================================================================
# 彩票历史查询系统 - 一键部署脚本
# 适用：Ubuntu 22.04 LTS / Debian 12（虚拟机）
# 用法：bash deploy.sh
# ============================================================================
set -euo pipefail

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
step()  { echo -e "\n${BLUE}=== $1 ===${NC}"; }

# P0-6: 终端摘要脱敏 —— 仅显示首尾各 2 字符，中间用 *** 代替。
# 完整密钥仅写入 server/.env（chmod 600），不在终端/日志中明文出现。
mask_secret() {
  local s="$1"
  if [ -z "$s" ]; then echo "(未设置)"; return; fi
  local len=${#s}
  if [ "$len" -le 4 ]; then echo "****"; return; fi
  printf '%s***%s' "${s:0:2}" "${s: -2}"
}

# 当前目录（deploy/ 父目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# 默认配置
SERVER_PORT="${SERVER_PORT:-3000}"
TURN_HOST="${TURN_HOST:-}"
TURN_USER="${TURN_USER:-lottery}"
TURN_PASS="${TURN_PASS:-$(openssl rand -base64 12 | tr -d '/+=' | head -c 16)}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin123}"
JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 48 | tr -d '/+=')}"
TUNNEL_MODE="${TUNNEL_MODE:-quick}"   # quick | named
TUNNEL_NAME="${TUNNEL_NAME:-lottery}"
DOMAIN="${DOMAIN:-}"

step "1/6 检查系统"
if [[ "$EUID" -ne 0 ]]; then
  error "请用 root 执行：sudo bash deploy.sh"
  exit 1
fi
if [[ ! -f /etc/os-release ]]; then
  warn "未识别到 OS，假设是 Ubuntu/Debian"
fi
info "系统: $(grep '^PRETTY_NAME' /etc/os-release | cut -d'"' -f2)"
info "项目目录: $PROJECT_DIR"

step "2/6 安装系统依赖"
if ! command -v node &>/dev/null || [[ "$(node -v | cut -dv -f2 | cut -d. -f1)" -lt 18 ]]; then
  info "安装 Node.js 20 LTS"
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
else
  info "Node.js 已安装: $(node -v)"
fi
apt-get install -y git curl wget jq coturn ufw build-essential python3 2>&1 | tail -3
info "依赖就绪"

step "3/6 配置 server"
info "安装 npm 依赖"
cd "$PROJECT_DIR/server"
npm install --omit=dev 2>&1 | tail -3

# 生成 .env
info "生成 server/.env"
cat > "$PROJECT_DIR/server/.env" <<EOF
PORT=$SERVER_PORT
JWT_SECRET=$JWT_SECRET
ADMIN_USER=$ADMIN_USER
ADMIN_PASS=$ADMIN_PASS
TURN_HOST=$TURN_HOST
TURN_USER=$TURN_USER
TURN_PASS=$TURN_PASS
NODE_ENV=production
EOF
chmod 600 "$PROJECT_DIR/server/.env"
info ".env 已生成（密钥已随机化）"

# systemd 服务
info "创建 systemd 服务 lottery-server"
cat > /etc/systemd/system/lottery-server.service <<EOF
[Unit]
Description=Lottery History Query Server
After=network.target

[Service]
Type=simple
WorkingDirectory=$PROJECT_DIR/server
EnvironmentFile=$PROJECT_DIR/server/.env
ExecStart=/usr/bin/node src/index.js
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable lottery-server
systemctl restart lottery-server
sleep 2
if curl -s http://localhost:$SERVER_PORT/api/health | grep -q '"ok"'; then
  info "server 启动成功（端口 $SERVER_PORT）"
else
  error "server 启动失败，查看日志：journalctl -u lottery-server -n 50"
  exit 1
fi

step "4/6 配置防火墙"
ufw allow 22/tcp 2>&1 | tail -1
ufw allow $SERVER_PORT/tcp 2>&1 | tail -1
ufw allow 3478/tcp 2>&1 | tail -1   # TURN
ufw allow 3478/udp 2>&1 | tail -1
ufw allow 5349/tcp 2>&1 | tail -1   # TURNS
ufw allow 49152:65535/udp 2>&1 | tail -1  # TURN relay
ufw --force enable 2>&1 | tail -1
info "防火墙已开启"

step "5/6 配置 TURN 服务器（coturn）"
if [[ -z "$TURN_HOST" ]]; then
  PUBLIC_IP=$(curl -s --max-time 5 https://api.ipify.org 2>/dev/null || echo "")
  if [[ -n "$PUBLIC_IP" ]]; then
    TURN_HOST="$PUBLIC_IP"
    sed -i "s/^TURN_HOST=.*/TURN_HOST=$TURN_HOST/" "$PROJECT_DIR/server/.env"
    info "检测到公网 IP：$TURN_HOST"
  else
    # NAT 环境：用 0.0.0.0 监听 + 留空 external-ip（coturn 自行探测）
    warn "无法获取公网 IP（NAT/内网环境），coturn 使用 0.0.0.0 监听，WebRTC 仍可通过 STUN 和 Cloudflare Tunnel 工作"
    TURN_HOST="0.0.0.0"
  fi
fi

cat > /etc/turnserver.conf <<EOF
listening-port=3478
tls-listening-port=5349
listening-ip=0.0.0.0
$( [[ "$TURN_HOST" != "0.0.0.0" ]] && echo "external-ip=$TURN_HOST" )
min-port=49152
max-port=65535
fingerprint
lt-cred-mech
user=$TURN_USER:$TURN_PASS
realm=lottery.local
total-quota=100
bps-capacity=0
no-cli
log-file=/var/log/turnserver.log
EOF
systemctl enable coturn 2>&1 | tail -1
systemctl restart coturn 2>&1 | tail -1
sleep 1
if systemctl is-active --quiet coturn; then
  info "coturn 已启动（用户=$TURN_USER 密码=$TURN_PASS）"
else
  warn "coturn 启动异常，但不影响 HTTP/WS 功能；WebRTC 回退到公共 STUN"
fi

step "6/6 配置 Cloudflare Tunnel"
if ! command -v cloudflared &>/dev/null; then
  info "安装 cloudflared"
  curl -L --output /tmp/cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
  dpkg -i /tmp/cloudflared.deb
fi

TUNNEL_URL=""
if [[ "$TUNNEL_MODE" == "named" && -n "$DOMAIN" ]]; then
  # 命名隧道（需用户已 cloudflared login）
  info "尝试创建命名隧道：$TUNNEL_NAME"
  if cloudflared tunnel list 2>/dev/null | grep -q "$TUNNEL_NAME"; then
    info "隧道已存在，复用"
  else
    cloudflared tunnel create "$TUNNEL_NAME" || warn "创建隧道失败，请先执行 cloudflared login"
  fi
  cloudflared tunnel route dns "$TUNNEL_NAME" "$DOMAIN" 2>&1 | tail -1
  cat > /etc/cloudflared/config.yml <<EOF
tunnel: $TUNNEL_NAME
credentials-file: /root/.cloudflared/<TUNNEL_ID>.json
ingress:
  - hostname: $DOMAIN
    service: http://localhost:$SERVER_PORT
  - service: http_status:404
EOF
  sed -i "s|<TUNNEL_ID>|$(cloudflared tunnel list 2>/dev/null | awk '/$TUNNEL_NAME/{print $2}')|g" /etc/cloudflared/config.yml
  cloudflared service install 2>&1 | tail -1
  TUNNEL_URL="https://$DOMAIN"
else
  # 临时隧道（免费，免登录）
  info "启动临时隧道（trycloudflare.com，每次重启会变）"
  cat > /etc/systemd/system/cloudflared-quick.service <<EOF
[Unit]
Description=Cloudflare Quick Tunnel
After=network.target

[Service]
Type=simple
ExecStart=/usr/bin/cloudflared tunnel --url http://localhost:$SERVER_PORT --no-autoupdate
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable cloudflared-quick
  systemctl restart cloudflared-quick
  info "等待 Cloudflare Tunnel 获取临时 URL（最多 30 秒）..."
  for i in {1..15}; do
    sleep 2
    TUNNEL_URL=$(journalctl -u cloudflared-quick --no-pager -n 50 2>/dev/null | grep -oE 'https://[a-zA-Z0-9-]+\.trycloudflare\.com' | head -1)
    if [[ -n "$TUNNEL_URL" ]]; then break; fi
    echo -n "."
  done
  echo ""
  if [[ -z "$TUNNEL_URL" ]]; then
    warn "未立即获取到临时 URL，稍后可通过 journalctl -u cloudflared-quick -f 查看"
  fi
fi

echo ""
step "✅ 部署完成"
cat <<EOF

${GREEN}================ 部署信息 ================${NC}
Web 后台：${BLUE}${TUNNEL_URL:-http://<虚拟机IP>:$SERVER_PORT}${NC}
管理员账号：${YELLOW}$ADMIN_USER / $(mask_secret "$ADMIN_PASS")${NC}（首次登录后立即改密！完整密码见 server/.env）
JWT_SECRET：$(mask_secret "$JWT_SECRET")（完整值见 server/.env，已 chmod 600）
TURN 服务器：$TURN_HOST:3478  用户 $TURN_USER / 密码 $(mask_secret "$TURN_PASS")（完整值见 server/.env）
WebSocket：${TUNNEL_URL:-http://<虚拟机IP>:$SERVER_PORT}/ws

${YELLOW}下一步：${NC}
1. 浏览器访问上面的 Web 后台，改管理员密码
2. 构建 release APK 时把 ${BLUE}ApiClient.BASE_URL${NC} 改为上面的公网 URL
3. 把用户 App 装到手机，注册并测试

查看服务状态：
  systemctl status lottery-server
  systemctl status coturn
  systemctl status cloudflared-quick
  journalctl -u cloudflared-quick -f   # 看隧道 URL

${RED}注意：${NC}临时隧道每次重启服务 URL 会变，建议用 named tunnel + 自有域名
EOF
