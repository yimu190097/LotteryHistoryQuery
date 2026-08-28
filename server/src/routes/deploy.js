const express = require('express');
const { exec } = require('child_process');
const fs = require('fs');
const router = express.Router();

const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || '';
const UPDATE_SCRIPT_URL = 'https://raw.githubusercontent.com/yimu190097/LotteryHistoryQuery/main/deploy/update.sh';
const UPDATE_SCRIPT_PATH = '/tmp/lottery-update.sh';
const LOG_PATH = '/var/log/lottery-update.log';
const RESULT_LOG = '/var/log/lottery-deploy-result.log';

// 部署 webhook：POST /api/deploy/webhook
// 异步执行部署，结果写入独立日志文件
router.post('/webhook', (req, res) => {
  if (!WEBHOOK_SECRET) {
    return res.status(500).json({ error: 'WEBHOOK_SECRET 未配置，请在 .env 中设置' });
  }

  const token = req.headers['x-webhook-secret'];
  if (!token || token !== WEBHOOK_SECRET) {
    return res.status(403).json({ error: '无效的 webhook 密钥' });
  }

  console.log('[Webhook] 收到部署请求');

  // 异步执行：先下载最新脚本，再执行部署
  const deployCmd = `curl -fsSL --connect-timeout 15 --max-time 30 "${UPDATE_SCRIPT_URL}" -o "${UPDATE_SCRIPT_PATH}" 2>&1 && bash "${UPDATE_SCRIPT_PATH}" 2>&1`;
  const deployId = Date.now().toString(36);

  // 写入开始标记
  try {
    fs.writeFileSync(RESULT_LOG, `[${new Date().toISOString()}] deploy_id=${deployId} 开始部署...\n`, { flag: 'w' });
  } catch (e) {
    // ignore
  }

  exec(deployCmd, { timeout: 180000 }, (err, stdout, stderr) => {
    const now = new Date().toISOString();
    let resultLog = `[${now}] deploy_id=${deployId}\n`;

    if (err) {
      resultLog += `状态: 失败\n错误: ${err.message}\n--- stdout ---\n${stdout || '(无)'}\n--- stderr ---\n${stderr || '(无)'}\n`;
      console.error(`[Webhook] 部署失败: ${err.message}`);
    } else {
      const success = (stdout || '').includes('更新成功') || (stdout || '').includes('文件同步完成');
      resultLog += `状态: ${success ? '成功' : '异常'}\n--- 输出 ---\n${stdout || '(无)'}\n`;
      console.log(`[Webhook] 部署完成, 状态: ${success ? '成功' : '异常'}`);
    }

    try {
      fs.writeFileSync(RESULT_LOG, resultLog, { flag: 'w' });
    } catch (e) {
      // ignore
    }
  });

  // 立即返回，不阻塞
  res.json({
    status: 'deploying',
    deploy_id: deployId,
    message: '部署已触发，正在后台执行（约30-120秒）',
    check_log: '/api/deploy/webhook/log',
    timestamp: new Date().toISOString()
  });
});

// 查看部署结果
router.get('/webhook/log', (req, res) => {
  // 优先读取结果日志
  try {
    const result = fs.readFileSync(RESULT_LOG, 'utf-8').trim();
    if (result) {
      res.json({ log: result });
      return;
    }
  } catch (e) {
    // ignore
  }

  // 回退到更新日志
  try {
    const log = exec('tail -n 50 "' + LOG_PATH + '" 2>/dev/null || echo "(暂无日志)"', {
      encoding: 'utf-8',
      timeout: 5000
    });
    res.json({ log: log.trim() });
  } catch (e) {
    res.json({ log: '读取日志失败: ' + e.message });
  }
});

module.exports = router;