const express = require('express');
const { db } = require('../db/database');
const { authMiddleware, userAuthMiddleware } = require('../middleware/auth');
const path = require('path');
const fs = require('fs');

const router = express.Router();

const DOWNLOADS_DIR = path.join(__dirname, '..', '..', 'public', 'downloads');

// 默认 ICE 服务器配置（可被环境变量覆盖）
const ICE_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' }
];

// 如果配置了自建 coturn，加入 TURN 服务器
if (process.env.TURN_HOST) {
  const turnUser = process.env.TURN_USER || 'lottery';
  const turnPass = process.env.TURN_PASS || 'lottery2026';
  ICE_SERVERS.push({
    urls: `turn:${process.env.TURN_HOST}:3478`,
    username: turnUser,
    credential: turnPass
  });
}

/**
 * GET /api/config/ice - 获取 WebRTC ICE 服务器配置
 * 客户端用户和管理员都可以调用
 */
router.get('/ice', authMiddleware, (req, res) => {
  res.json({ iceServers: ICE_SERVERS });
});

/**
 * GET /api/config - 获取系统配置
 */
router.get('/', (req, res) => {
  const configs = db.prepare('SELECT * FROM system_config').all();
  const result = {};
  configs.forEach(c => { result[c.key] = c.value; });
  res.json(result);
});

/**
 * PUT /api/config/:key - 更新系统配置
 */
router.put('/:key', (req, res) => {
  const { key } = req.params;
  const { value } = req.body;
  if (!value) {
    return res.status(400).json({ error: '值不能为空' });
  }

  db.prepare('INSERT OR REPLACE INTO system_config (key, value, updated_at) VALUES (?, ?, ?)')
    .run(key, value, Date.now());

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'UPDATE_CONFIG', key, value, Date.now());

  res.json({ message: '配置更新成功' });
});

/**
 * GET /api/config/admins - 管理员列表
 */
router.get('/admins', (req, res) => {
  const admins = db.prepare(
    'SELECT id, username, role, created_at, last_login FROM admins ORDER BY id'
  ).all();
  res.json(admins);
});

/**
 * POST /api/config/admins - 创建管理员
 */
router.post('/admins', (req, res) => {
  const { username, password, role } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: '用户名和密码不能为空' });
  }

  const existing = db.prepare('SELECT id FROM admins WHERE username = ?').get(username);
  if (existing) {
    return res.status(400).json({ error: '管理员已存在' });
  }

  const bcrypt = require('bcryptjs');
  const hash = bcrypt.hashSync(password, 10);
  db.prepare('INSERT INTO admins (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)')
    .run(username, hash, role || 'admin', Date.now());

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'CREATE_ADMIN', username, '', Date.now());

  res.json({ message: '管理员创建成功' });
});

/**
 * POST /api/config/apk-delete - 删除 APK 文件
 */
router.post('/apk-delete', authMiddleware, (req, res) => {
  const { filename } = req.body;
  if (!filename) return res.status(400).json({ error: '文件名不能为空' });

  // 安全检查：防止路径穿越
  const safeName = path.basename(filename);
  if (!safeName.endsWith('.apk')) return res.status(400).json({ error: '仅支持删除 .apk 文件' });

  const filePath = path.join(DOWNLOADS_DIR, safeName);
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: '文件不存在' });

  fs.unlinkSync(filePath);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'DELETE_APK', safeName, '删除APK文件', Date.now());

  res.json({ message: 'APK 已删除' });
});

module.exports = router;
