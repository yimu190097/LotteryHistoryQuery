const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');
const http = require('http');
const multer = require('multer');
const rateLimit = require('express-rate-limit');
const { initTables } = require('./db/database');
const { setupWebSocket } = require('./ws/chatServer');
const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const statsRoutes = require('./routes/stats');
const configRoutes = require('./routes/config');
const chatRoutes = require('./routes/chat');
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
  // APK 纯静态文件下载允许所有来源（手机浏览器 direct link）
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

// P0-4 安全加固：管理员登录 API 限流 — 同 IP 5分钟内最多 10 次，防暴力破解
const loginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '登录尝试过于频繁，5 分钟后再试' },
  keyGenerator: (req) => req.ip,
});

// 客户端用户登录限流：同 IP 5分钟内最多 20 次
const clientLoginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '登录尝试过于频繁，5 分钟后再试' },
});

// 客户端注册限流：同 IP 每小时最多 20 个，防刷号
const registerLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '注册过于频繁，1 小时后再试' },
});

// 全局 API 限流：防 DoS — 每分钟最多 300 次请求/IP
const globalApiLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '请求过频，请稍后再试' },
  skip: (req) => req.path.startsWith('/downloads/') || req.path.startsWith('/uploads/'), // 静态下载文件跳过
});

// 初始化数据库
initTables();

// 中间件
app.use(cors(corsOptions));
app.use(express.json({ limit: '5mb' }));
app.use('/api', globalApiLimiter);
app.use(morgan('short'));

// 健康检查（提前到限流前不卡）
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

// 登录/注册 挂载限流（在 authRoutes 之前生效）
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
  limits: { fileSize: 10 * 1024 * 1024 } // 10MB
});

// APK 上传（独立 multer 实例，文件大小限制 100MB）
const apkUpload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, DOWNLOADS_DIR),
    filename: (req, file, cb) => {
      // 保持原始文件名，覆盖旧版本
      cb(null, file.originalname);
    }
  }),
  limits: { fileSize: 100 * 1024 * 1024 } // 100MB
});

// API 路由
app.use('/api/auth', authRoutes);
app.use('/api/users', userRoutes);
app.use('/api/stats', statsRoutes);
app.use('/api/config', configRoutes);
app.use('/api/chat', chatRoutes);

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

// SPA fallback - 所有非API/非静态资源请求返回index.html
app.get('*', (req, res) => {
  if (!req.path.startsWith('/api') && !req.path.startsWith('/uploads') && !req.path.startsWith('/downloads')) {
    res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
  } else if (req.path.startsWith('/api')) {
    res.status(404).json({ error: 'API不存在' });
  }
});

// 错误处理（含 CORS 拒绝 → 返回 JSON 而非 HTML）
app.use((err, req, res, next) => {
  console.error('[ERROR]', err.message || err);
  if (err.message && err.message.startsWith('CORS blocked')) {
    return res.status(403).json({ error: '来源未授权（不在 CORS 白名单中）' });
  }
  if (err.code === 'LIMIT_FILE_SIZE') {
    return res.status(413).json({ error: '文件超过10MB限制' });
  }
  // rate limit 错误直接透传（express-rate-limit 已返回 JSON）
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
