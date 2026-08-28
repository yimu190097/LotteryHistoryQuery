const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');
const http = require('http');
const multer = require('multer');
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

// 初始化数据库
initTables();

// 中间件
app.use(cors());
app.use(express.json({ limit: '5mb' }));
app.use(morgan('short'));

// 静态文件 - Web管理面板
app.use(express.static(path.join(__dirname, '..', 'public')));

// 上传文件目录（图片/语音）
const UPLOAD_DIR = path.join(__dirname, '..', 'public', 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

// APK 下载目录
const DOWNLOADS_DIR = path.join(__dirname, '..', 'public', 'downloads');
if (!fs.existsSync(DOWNLOADS_DIR)) fs.mkdirSync(DOWNLOADS_DIR, { recursive: true });

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

// 健康检查
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

// SPA fallback - 所有非API请求返回index.html
app.get('*', (req, res) => {
  if (!req.path.startsWith('/api') && !req.path.startsWith('/uploads')) {
    res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
  } else if (req.path.startsWith('/api')) {
    res.status(404).json({ error: 'API不存在' });
  }
});

// 错误处理
app.use((err, req, res, next) => {
  console.error('[ERROR]', err);
  if (err.code === 'LIMIT_FILE_SIZE') {
    return res.status(413).json({ error: '文件超过10MB限制' });
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
