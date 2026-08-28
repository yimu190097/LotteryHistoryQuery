const express = require('express');
const { exec } = require('child_process');
const router = express.Router();

const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || '';

// 部署 webhook：POST /api/deploy/webhook
// 需要 Header: X-Webhook-Secret
router.post('/webhook', (req, res) => {
  // 检查是否配置了 webhook secret
  if (!WEBHOOK_SECRET) {
    return res.status(500).json({ error: 'WEBHOOK_SECRET 未配置，请在 .env 中设置' });
  }

  const token = req.headers['x-webhook-secret'];
  if (!token || token !== WEBHOOK_SECRET) {
    return res.status(403).json({ error: '无效的 webhook 密钥' });
  }

  const scriptPath = '/root/lottery/deploy/update.sh';
  const logPath = '/var/log/lottery-update.log';

  // 异步执行更新脚本，不阻塞响应
  console.log('[Webhook] 收到部署请求，执行更新脚本...');
  exec(`bash ${scriptPath}`, { timeout: 120000 }, (err, stdout, stderr) => {
    if (err) {
      console.error(`[Webhook] 更新失败: ${err.message}`);
      console.error(`[Webhook] stderr: ${stderr}`);
    } else {
      console.log(`[Webhook] 更新完成: ${stdout}`);
    }
  });

  res.json({
    status: 'deploying',
    message: '部署已触发，正在后台更新...',
    log: logPath,
    timestamp: new Date().toISOString()
  });
});

// 查看最近更新日志
router.get('/webhook/log', (req, res) => {
  const { execSync } = require('child_process');
  try {
    const log = execSync('tail -n 30 /var/log/lottery-update.log 2>/dev/null || echo "(暂无日志)"', {
      encoding: 'utf-8',
      timeout: 5000
    });
    res.json({ log: log.trim() });
  } catch (e) {
    res.json({ log: `读取日志失败: ${e.message}` });
  }
});

module.exports = router;