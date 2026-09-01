#!/bin/bash
# 彩票开奖查询 - 一键启动脚本
# 用法: bash start.sh

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/server"

# 1. 安装依赖
if [ ! -d "node_modules" ]; then
  echo "[1/4] 安装依赖..."
  npm install
else
  echo "[1/4] 依赖已就绪"
fi

# 2. 配置 ngrok（仅首次）
if [ ! -f ~/.config/ngrok/ngrok.yml ]; then
  echo "[2/4] 配置 ngrok..."
  TOKEN_FILE="$SCRIPT_DIR/.ngrok_token"
  if [ -f "$TOKEN_FILE" ]; then
    ngrok config add-authtoken "$(cat "$TOKEN_FILE")"
  else
    echo "  未找到 ngrok token，跳过隧道配置"
    echo "  将 token 写入 $TOKEN_FILE 后重新运行"
  fi
else
  echo "[2/4] ngrok 已配置"
fi

# 3. 启动 Node 服务器
echo "[3/4] 启动 Node 服务器..."
kill "$(lsof -t -i:3000)" 2>/dev/null || true
sleep 1
node src/index.js > /tmp/server.log 2>&1 &
sleep 2
echo "  服务器已启动: http://localhost:3000"

# 固定域名（ngrok 静态域名，免费版送 1 个）
# 在 https://dashboard.ngrok.com/domains 领取后填这里
NGROK_DOMAIN="showbiz-unbridle-decent.ngrok-free.dev"

# 4. 启动 ngrok 隧道（用固定域名，重启网址不变）
if [ -f ~/.config/ngrok/ngrok.yml ]; then
  echo "[4/4] 启动 ngrok 隧道..."
  pkill ngrok 2>/dev/null || true
  sleep 1
  nohup ngrok http --domain="$NGROK_DOMAIN" 3000 --log=stdout > /tmp/ngrok.log 2>&1 &
  sleep 3
  echo ""
  echo "========================================="
  echo "  固定网址:   https://$NGROK_DOMAIN"
  echo "  网页版:     https://$NGROK_DOMAIN/web/"
  echo "  管理后台:   https://$NGROK_DOMAIN/"
  echo "========================================="
else
  echo "[4/4] 跳过 ngrok（未配置 token）"
fi

echo ""
echo "启动完成。查看日志:"
echo "  服务器: tail -f /tmp/server.log"
echo "  ngrok:  tail -f /tmp/ngrok.log"