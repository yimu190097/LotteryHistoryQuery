const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const { initTables } = require('./db/database');
const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const statsRoutes = require('./routes/stats');
const configRoutes = require('./routes/config');

const app = express();
const PORT = process.env.PORT || 3000;

// 初始化数据库
initTables();

// 中间件
app.use(cors());
app.use(express.json());
app.use(morgan('short'));

// 静态文件 - Web管理面板
app.use(express.static(path.join(__dirname, '..', 'public')));

// API 路由
app.use('/api/auth', authRoutes);
app.use('/api/users', userRoutes);
app.use('/api/stats', statsRoutes);
app.use('/api/config', configRoutes);

// 健康检查
app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

// SPA fallback - 所有非API请求返回index.html
app.get('*', (req, res) => {
  if (!req.path.startsWith('/api')) {
    res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
  } else {
    res.status(404).json({ error: 'API不存在' });
  }
});

// 错误处理
app.use((err, req, res, next) => {
  console.error('[ERROR]', err);
  res.status(500).json({ error: '服务器内部错误' });
});

app.listen(PORT, () => {
  console.log(`[Server] 彩票后台管理服务器已启动: http://localhost:${PORT}`);
  console.log(`[Server] 管理面板: http://localhost:${PORT}`);
  console.log(`[Server] API文档: http://localhost:${PORT}/api/health`);
});