const express = require('express');
const { execSync } = require('child_process');
const fs = require('fs');
const router = express.Router();

const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || '';
const UPDATE_SCRIPT_URL = 'https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main/deploy/update.sh';
const UPDATE_SCRIPT_PATH = '/tmp/lottery-update.sh';
const LOG_PATH = '/var/log/lottery-update.log';

// 部署 webhook：POST /api/deploy/webhook
// 自动下载最新脚本 → 同步执行 → 返回结果
router.post('/webhook', (req, res) => {
  if (!WEBHOOK_SECRET) {
    return res.status(500).json({ error: 'WEBHOOK_SECRET 未配置，请在 .env 中设置' });
  }

  const token = req.headers['x-webhook-secret'];
  if (!token || token !== WEBHOOK_SECRET) {
    return res.status(403).json({ error: '无效的 webhook 密钥' });
  }

  console.log('[Webhook] 收到部署请求');

  try {
    // 步骤 1: 下载最新部署脚本（从 raw.githubusercontent.com，VM 可访问）
    console.log('[Webhook] 下载最新部署脚本...');
    execSync('curl -fsSL --connect-timeout 15 --max-time 30 "' + UPDATE_SCRIPT_URL + '" -o "' + UPDATE_SCRIPT_PATH + '"', {
      encoding: 'utf-8',
      timeout: 35000
    });

    // 步骤 2: 执行部署脚本（同步等待结果）
    console.log('[Webhook] 执行部署脚本...');
    const output = execSync('bash "' + UPDATE_SCRIPT_PATH + '" 2>&1', {
      encoding: 'utf-8',
      timeout: 180000
    });

    console.log('[Webhook] 部署完成');

    // 步骤 3: 读取日志
    let logTail = '';
    try {
      logTail = execSync('tail -n 20 "' + LOG_PATH + '" 2>/dev/null || echo "(无日志)"', {
        encoding: 'utf-8',
        timeout: 5000
      }).trim();
    } catch (e) {
      logTail = '(读取日志失败)';
    }

    const success = output.includes('更新成功') || output.includes('文件同步完成');

    res.json({
      status: success ? 'success' : 'failed',
      message: success ? '部署成功，服务已重启' : '部署完成，但健康检查未通过',
      output: output.trim().split('\n').slice(-30).join('\n'),
      log_tail: logTail,
      timestamp: new Date().toISOString()
    });
  } catch (err) {
    console.error('[Webhook] 部署失败:', err.message);

    let logTail = '';
    try {
      logTail = execSync('tail -n 20 "' + LOG_PATH + '" 2>/dev/null || echo "(无日志)"', {
        encoding: 'utf-8',
        timeout: 5000
      }).trim();
    } catch (e) {
      logTail = '(读取日志失败)';
    }

    res.status(500).json({
      status: 'error',
      message: '部署失败: ' + err.message,
      error: err.message,
      stderr: err.stderr || '',
      stdout: err.stdout || '',
      log_tail: logTail,
      timestamp: new Date().toISOString()
    });
  }
});

// 查看最近更新日志
router.get('/webhook/log', (req, res) => {
  try {
    const log = execSync('tail -n 50 "' + LOG_PATH + '" 2>/dev/null || echo "(暂无日志)"', {
      encoding: 'utf-8',
      timeout: 5000
    });
    res.json({ log: log.trim() });
  } catch (e) {
    res.json({ log: '读取日志失败: ' + e.message });
  }
});

module.exports = router;