#!/bin/bash
# 彩票开奖查询 - 一键启动脚本
# 用法: bash start.sh

set -e
cd /workspace/LotteryHistoryQuery/server

# 1. 安装依赖（首次运行或 node_modules 丢失时）
if [ ! -d "node_modules" ]; then
  echo "[1/4] 安装依赖..."
  npm install
else
  echo "[1/4] 依赖已就绪"
fi

# 2. 配置 ngrok（仅首次）
if [ ! -f ~/.config/ngrok/ngrok.yml ]; then
  echo "[2/4] 配置 ngrok..."
  TOKEN_FILE="/workspace/LotteryHistoryQuery/.ngrok_token"
  if [ -f "$TOKEN_FILE" ]; then
    ngrok config add-authtoken $(cat "$TOKEN_FILE")
  else
    echo "⚠ 未找到 ngrok token，跳过隧道配置"
    echo "  将 token 写入 $TOKEN_FILE 后重新运行"
  fi
else
  echo "[2/4] ngrok 已配置"
fi

# 3. 启动 Node 服务器
echo "[3/4] 启动 Node 服务器..."
kill $(lsof -t -i:3000) 2>/dev/null || true
sleep 1
JWT_SECRET="lottery-prod-secret-v2" node src/index.js > /tmp/server.log 2>&1 &
sleep 2
echo "  服务器已启动: http://localhost:3000"

# 4. 启动 ngrok 隧道
if [ -f ~/.config/ngrok/ngrok.yml ]; then
  echo "[4/4] 启动 ngrok 隧道..."
  pkill ngrok 2>/dev/null || true
  sleep 1
  nohup ngrok http 3000 --log=stdout > /tmp/ngrok.log 2>&1 &
  sleep 3
  URL=$(curl -s http://localhost:4040/api/tunnels 2>/dev/null | grep -o '"public_url":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -n "$URL" ]; then
    echo ""
    echo "========================================="
    echo "  公网地址: $URL/web/"
    echo "  管理后台: $URL/"
    echo "========================================="
  else
    echo "  隧道启动中，查看状态: curl http://localhost:4040/api/tunnels"
  fi
else
  echo "[4/4] 跳过 ngrok（未配置 token）"
fi

echo ""
echo "启动完成。查看日志:"
echo "  服务器: tail -f /tmp/server.log"
echo "  ngrok:  tail -f /tmp/ngrok.log"
echo "  公网URL: curl -s http://localhost:4040/api/tunnels | grep public_url"