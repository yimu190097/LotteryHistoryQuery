#!/usr/bin/env bash
# ============================================================================
# 服务健康检查
# 用法：bash deploy/health-check.sh [host] [admin-user] [admin-pass]
# 例如：bash deploy/health-check.sh http://localhost:3000 admin admin123
# ============================================================================
set -euo pipefail
HOST="${1:-http://localhost:3000}"
ADMIN_USER="${2:-admin}"
# 默认密码仅作占位；若仍为 admin123 视为"未提供"，跳过需鉴权的登录检查
ADMIN_PASS="${3:-}"

# 每个 curl 均设超时，避免健康检查自身挂死
CURL="curl --max-time 10 -s"

GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
fail() { echo -e "${RED}✗ $1${NC}"; FAIL=1; }
FAIL=0

echo "检查目标：$HOST"

# HTTP 健康检查
if $CURL -o /dev/null -w "%{http_code}" "$HOST/api/health" | grep -q "200"; then
  ok "HTTP /api/health OK"
else
  fail "HTTP /api/health 失败"
fi

# WebSocket 升级支持
WS_CODE=$($CURL -o /dev/null -w "%{http_code}" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  "$HOST/ws")
# 101=升级成功；400/401/426=端点存在但因鉴权/协议被拒，仍证明 /ws 已挂载
if [[ "$WS_CODE" == "101" || "$WS_CODE" == "426" || "$WS_CODE" == "400" || "$WS_CODE" == "401" ]]; then
  ok "WebSocket /ws 端点可达（HTTP $WS_CODE）"
else
  fail "WebSocket /ws 端点异常（HTTP $WS_CODE）"
fi

# 管理员登录（仅当显式传入有效密码时执行，否则跳过以免用默认密码误报）
if [[ -n "$ADMIN_PASS" && "$ADMIN_PASS" != "admin123" && "$ADMIN_PASS" != "changeme" ]]; then
LOGIN_RESP=$($CURL -X POST "$HOST/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
if echo "$LOGIN_RESP" | grep -q "token"; then
  ok "管理员登录 OK"
  TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
  # 会话列表
  if $CURL -H "Authorization: Bearer $TOKEN" "$HOST/api/chat/sessions" | grep -q "data"; then
    ok "GET /api/chat/sessions OK"
  else
    fail "GET /api/chat/sessions 失败"
  fi
  # ICE 配置
  if $CURL -H "Authorization: Bearer $TOKEN" "$HOST/api/config/ice" | grep -q "iceServers"; then
    ok "GET /api/config/ice OK"
  else
    fail "GET /api/config/ice 失败"
  fi
else
  fail "管理员登录失败（账号=$ADMIN_USER / 响应=$LOGIN_RESP）"
fi
else
  echo -e "${GREEN}✓ 跳过管理员登录检查（未提供有效密码，传第 3 参数可启用）${NC}"
fi

# 静态资源
if $CURL -o /dev/null -w "%{http_code}" "$HOST/js/call.js" | grep -q "200"; then
  ok "GET /js/call.js 静态资源 OK"
else
  fail "GET /js/call.js 失败"
fi

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo -e "${GREEN}✅ 所有检查通过${NC}"
else
  echo -e "${RED}❌ 存在失败项，请检查${NC}"
  exit 1
fi
