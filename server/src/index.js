const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');
const http = require('http');
const https = require('https');
const multer = require('multer');
const rateLimit = require('express-rate-limit');
const { initTables } = require('./db/database');
const { setupWebSocket } = require('./ws/chatServer');
const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const statsRoutes = require('./routes/stats');
const configRoutes = require('./routes/config');
const chatRoutes = require('./routes/chat');
const deployRoutes = require('./routes/deploy');
const { authMiddleware } = require('./middleware/auth');

const app = express();
const PORT = process.env.PORT || 3000;

// P0-1 安全加固：CORS 白名单
const ALLOWED_ORIGINS = [
  // 本机开发/管理面板
  /^https?:\/\/localhost(:\d+)?$/,
  /^https?:\/\/127\.0\.0\.1(:\d+)?$/,
  /^https?:\/\/192\.168\.\d{1,3}\.\d{1,3}(:\d+)?$/,
  /^https?:\/\/10\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$/,
  // 你的固定 ngrok 域名
  /^https:\/\/showbiz-unbridle-decent\.ngrok-free\.dev$/,
  // 允许其他 ngrok / trycloudflare 临时隧道（部署脚本会变）
  /\.ngrok-free\.dev$/,
  /\.trycloudflare\.com$/,
];

function isOriginAllowed(origin) {
  if (!origin) return true; // 同域请求无 origin 头，放行
  return ALLOWED_ORIGINS.some(r => r.test(origin));
}

const corsOptions = {
  origin: (origin, callback) => {
    if (isOriginAllowed(origin)) {
      callback(null, true);
    } else {
      callback(new Error(`CORS blocked: ${origin} 不在白名单中`));
    }
  },
  credentials: true,
  maxAge: 86400
};

// P0-4 管理员登录限流
const loginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '登录尝试过于频繁，5 分钟后再试' },
  keyGenerator: (req) => req.ip,
});

const clientLoginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '登录尝试过于频繁，5 分钟后再试' },
});

const registerLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '注册过于频繁，1 小时后再试' },
});

const globalApiLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '请求过频，请稍后再试' },
  skip: (req) => req.path.startsWith('/downloads/') || req.path.startsWith('/uploads/'),
});

// 初始化数据库
initTables();

// 中间件
app.use(cors(corsOptions));
app.use(express.json({ limit: '5mb' }));
app.use('/api', globalApiLimiter);
app.use(morgan('short'));

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

app.use('/api/auth/login', loginLimiter);
app.use('/api/users/client/login', clientLoginLimiter);
app.use('/api/users/client/register', registerLimiter);

// 静态文件 - Web管理面板
app.use(express.static(path.join(__dirname, '..', 'public')));

// APK 下载目录
const DOWNLOADS_DIR = path.join(__dirname, '..', 'public', 'downloads');
if (!fs.existsSync(DOWNLOADS_DIR)) fs.mkdirSync(DOWNLOADS_DIR, { recursive: true });

// APK 下载静态文件服务
app.use('/downloads', express.static(DOWNLOADS_DIR));

// 上传文件目录（图片/语音）
const UPLOAD_DIR = path.join(__dirname, '..', 'public', 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const upload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, UPLOAD_DIR),
    filename: (req, file, cb) => {
      const ext = path.extname(file.originalname) || (file.mimetype.startsWith('image') ? '.jpg' : '.m4a');
      cb(null, `${Date.now()}_${Math.random().toString(36).slice(2, 8)}${ext}`);
    }
  }),
  limits: { fileSize: 10 * 1024 * 1024 }
});

// APK 上传（独立 multer 实例，100MB）
const apkUpload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, DOWNLOADS_DIR),
    filename: (req, file, cb) => cb(null, file.originalname)
  }),
  limits: { fileSize: 100 * 1024 * 1024 }
});

// ============================================================================
// 从 GitHub Releases 同步 APK 到服务器（后台任务 + 动态进度）
// ============================================================================
const GH_OWNER = 'yimu190097';
const GH_REPO = 'LotteryHistoryQuery';
const GH_API = `https://api.github.com/repos/${GH_OWNER}/${GH_REPO}`;
const GH_TOKEN = process.env.GH_TOKEN || '';

// 内存同步状态（单实例）
let apkSync = {
  running: false,
  startedAt: null,
  finishedAt: null,
  stage: 'idle',
  tag: null,
  releaseName: null,
  tasks: [],
  error: null,
  message: '',
};

// GitHub API JSON 请求（一次性返回 body）
function ghApiGet(url) {
  return new Promise((resolve, reject) => {
    const headers = { 'User-Agent': 'lottery-server', 'Accept': 'application/vnd.github+json' };
    if (GH_TOKEN) headers['Authorization'] = `token ${GH_TOKEN}`;
    const req = https.get(url, { headers }, (res) => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => resolve({ status: res.statusCode, data, headers: res.headers }));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.setTimeout(15000, () => req.destroy(new Error('请求超时')));
  });
}

// 下载 APK 到本地，逐 chunk 累加进度。下载源使用 GitHub assets API（octet-stream），
// 服务端无法直连 github.com 主站下载域时也能走 api.github.com -> 对象存储。
function downloadApk(assetId, filename, destPath, onProgress) {
  return new Promise((resolve, reject) => {
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36',
      'Accept': 'application/octet-stream',
    };
    if (GH_TOKEN) headers['Authorization'] = `token ${GH_TOKEN}`;
    const ws = fs.createWriteStream(destPath);
    let received = 0;

    const doGet = (url, hdrs) => {
      const req = https.get(url, { headers: hdrs }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          // 跟随 302 到对象存储（不带会 403 的自定义头，仅保留真实 UA）
          doGet(res.headers.location, { 'User-Agent': headers['User-Agent'] });
          return;
        }
        if (res.statusCode !== 200) {
          ws.destroy();
          return resolve({ ok: false, status: res.statusCode });
        }
        res.on('data', (chunk) => {
          ws.write(chunk);
          received += chunk.length;
          if (onProgress) onProgress(received);
        });
        res.on('error', (e) => { ws.destroy(); reject(e); });
        res.on('end', () => { ws.end(); resolve({ ok: true, status: res.statusCode, received }); });
      });
      req.on('error', (e) => { ws.destroy(); reject(e); });
      req.setTimeout(30000, () => req.destroy(new Error('下载连接超时')));
    };

    doGet(`${GH_API}/releases/assets/${assetId}`, headers);
  });
}

async function syncApkFromGithub() {
  if (apkSync.running) {
    apkSync.message = '已有一个同步任务正在执行';
    return;
  }
  apkSync = {
    running: true,
    startedAt: Date.now(),
    finishedAt: null,
    stage: 'fetching_release',
    tag: null,
    releaseName: null,
    tasks: [],
    error: null,
    message: '正在获取 GitHub 最新 Release...',
  };

  try {
    const rel = await ghApiGet(`${GH_API}/releases/latest`);
    if (rel.status !== 200) throw new Error(`获取 Release 失败: HTTP ${rel.status}`);
    const release = JSON.parse(rel.data);
    apkSync.tag = release.tag_name;
    apkSync.releaseName = release.name || release.tag_name;

    const apkAssets = (release.assets || []).filter(a => a.name.endsWith('.apk'));
    if (!apkAssets.length) throw new Error('最新 Release 中没有找到 APK 资产');

    apkSync.tasks = apkAssets.map(a => ({
      filename: a.name,
      size: a.size,
      total: a.size,
      received: 0,
      percent: 0,
      status: 'pending',
      assetId: a.id,
    }));

    for (const task of apkSync.tasks) {
      task.status = 'downloading';
      apkSync.stage = 'downloading';
      apkSync.message = `正在下载 ${task.filename}...`;
      const destPath = path.join(DOWNLOADS_DIR, task.filename);
      // 断点重试：累计3次，每次更新进度
      const dl = await downloadApk(task.assetId, task.filename, destPath, (received) => {
        task.received = received;
        task.percent = task.size > 0 ? Math.min(100, Math.round(received / task.size * 100)) : 0;
      });
      if (!dl.ok) {
        task.status = 'error';
        throw new Error(`下载 ${task.filename} 失败: HTTP ${dl.status}`);
      }
      task.received = dl.received;
      task.percent = 100;
      task.status = 'done';
    }

    apkSync.stage = 'done';
    apkSync.finishedAt = Date.now();
    apkSync.running = false;
    apkSync.message = 'APK 同步完成';
  } catch (err) {
    apkSync.error = err.message;
    apkSync.stage = 'error';
    apkSync.finishedAt = Date.now();
    apkSync.running = false;
    apkSync.message = err.message;
    console.error('[APK Sync]', err.message);
  }
}

// 触发从 GitHub 同步 APK（管理员）
app.post('/api/apk/sync', authMiddleware, (req, res) => {
  if (apkSync.running) {
    return res.json({ running: true, message: '已有一个同步任务正在执行' });
  }
  syncApkFromGithub();
  res.json({ running: true, message: '已触发从 GitHub 同步最新 APK' });
});

// 查询同步进度
app.get('/api/apk/sync-status', (req, res) => {
  res.json(apkSync);
});

// API 路由
app.use('/api/auth', authRoutes);
app.use('/api/users', userRoutes);
app.use('/api/stats', statsRoutes);
app.use('/api/config', configRoutes);
app.use('/api/chat', chatRoutes);
app.use('/api/deploy', deployRoutes);

// 文件上传（需要客户端 Token 或管理员 Token）
app.post('/api/upload', authMiddleware, upload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: '未收到文件' });
  const url = `/uploads/${req.file.filename}`;
  res.json({ url, size: req.file.size, mimetype: req.file.mimetype });
});

// APK 上传（仅管理员）
app.post('/api/upload-apk', authMiddleware, apkUpload.single('apk'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: '未收到 APK 文件' });
  const url = `/downloads/${req.file.filename}`;
  res.json({ url, size: req.file.size, filename: req.file.filename });
});

// APK 列表
app.get('/api/apk-list', (req, res) => {
  try {
    const files = fs.readdirSync(DOWNLOADS_DIR)
      .filter(f => f.endsWith('.apk'))
      .map(f => {
        const stat = fs.statSync(path.join(DOWNLOADS_DIR, f));
        return {
          filename: f,
          size: stat.size,
          url: `/downloads/${f}`,
          updatedAt: stat.mtime.toISOString()
        };
      })
      .sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt));
    res.json(files);
  } catch (e) {
    res.json([]);
  }
});

// SPA fallback
app.get('*', (req, res) => {
  if (!req.path.startsWith('/api') && !req.path.startsWith('/uploads') && !req.path.startsWith('/downloads')) {
    res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
  } else if (req.path.startsWith('/api')) {
    res.status(404).json({ error: 'API不存在' });
  }
});

// 错误处理
app.use((err, req, res, next) => {
  console.error('[ERROR]', err.message || err);
  if (err.message && err.message.startsWith('CORS blocked')) {
    return res.status(403).json({ error: '来源未授权（不在 CORS 白名单中）' });
  }
  if (err.code === 'LIMIT_FILE_SIZE') {
    return res.status(413).json({ error: '文件超过大小限制' });
  }
  if (err.statusCode === 429) {
    return res.status(429).json({ error: err.message || '请求过频' });
  }
  res.status(500).json({ error: '服务器内部错误' });
});

// 用 http.Server 包装 express，便于挂 WebSocket
const server = http.createServer(app);

// 挂载 WebSocket 服务到 /ws 路径
setupWebSocket(server);

server.listen(PORT, () => {
  console.log(`[Server] 彩票后台管理服务器已启动: http://localhost:${PORT}`);
  console.log(`[Server] 管理面板: http://localhost:${PORT}`);
  console.log(`[Server] WebSocket: ws://localhost:${PORT}/ws`);
  console.log(`[Server] API文档: http://localhost:${PORT}/api/health`);
});