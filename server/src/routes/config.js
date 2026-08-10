const express = require('express');
const { db } = require('../db/database');
const { authMiddleware } = require('../middleware/auth');

const router = express.Router();

// 所有接口需要登录
router.use(authMiddleware);

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

module.exports = router;