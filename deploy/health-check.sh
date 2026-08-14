#!/usr/bin/env bash
# ============================================================================
# 服务健康检查
# 用法：bash deploy/health-check.sh [host] [admin-user] [admin-pass]
# 例如：bash deploy/health-check.sh http://localhost:3000 admin admin123
# ============================================================================
set -euo pipefail
HOST="${1:-http://localhost:3000}"
ADMIN_USER="${2:-admin}"
ADMIN_PASS="${3:-admin123}"

GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
fail() { echo -e "${RED}✗ $1${NC}"; FAIL=1; }
FAIL=0

echo "检查目标：$HOST"

# HTTP 健康检查
if curl -s -o /dev/null -w "%{http_code}" "$HOST/api/health" | grep -q "200"; then
  ok "HTTP /api/health OK"
else
  fail "HTTP /api/health 失败"
fi

# WebSocket 升级支持
WS_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  "$HOST/ws")
if [[ "$WS_CODE" == "101" || "$WS_CODE" == "426" || "$WS_CODE" == "400" || "$WS_CODE" == "401" ]]; then
  ok "WebSocket /ws 端点可达（HTTP $WS_CODE）"
else
  fail "WebSocket /ws 端点异常（HTTP $WS_CODE）"
fi

# 管理员登录
LOGIN_RESP=$(curl -s -X POST "$HOST/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")
if echo "$LOGIN_RESP" | grep -q "token"; then
  ok "管理员登录 OK"
  TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
  # 会话列表
  if curl -s -H "Authorization: Bearer $TOKEN" "$HOST/api/chat/sessions" | grep -q "data"; then
    ok "GET /api/chat/sessions OK"
  else
    fail "GET /api/chat/sessions 失败"
  fi
  # ICE 配置
  if curl -s -H "Authorization: Bearer $TOKEN" "$HOST/api/config/ice" | grep -q "iceServers"; then
    ok "GET /api/config/ice OK"
  else
    fail "GET /api/config/ice 失败"
  fi
else
  fail "管理员登录失败（账号=$ADMIN_USER / 响应=$LOGIN_RESP）"
fi

# 静态资源
if curl -s -o /dev/null -w "%{http_code}" "$HOST/js/call.js" | grep -q "200"; then
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
