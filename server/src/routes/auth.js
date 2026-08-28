const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/database');
const { generateToken, authMiddleware } = require('../middleware/auth');

const router = express.Router();

/**
 * POST /api/auth/login - 管理员登录
 */
router.post('/login', (req, res) => {
  const { username, password } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: '用户名和密码不能为空' });
  }

  const admin = db.prepare('SELECT * FROM admins WHERE username = ?').get(username);
  if (!admin) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }

  if (!bcrypt.compareSync(password, admin.password_hash)) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }

  // 更新最后登录时间
  db.prepare('UPDATE admins SET last_login = ? WHERE id = ?').run(Date.now(), admin.id);

  // 记录日志
  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, detail, created_at) VALUES (?, ?, ?, ?, ?)'
  ).run(admin.id, admin.username, 'LOGIN', '管理员登录' + (admin.must_change_password ? '（需强制改密）' : ''), Date.now());

  const token = generateToken(admin);

  // P0-3: 如果标记了 must_change_password=1 → 返回标记，前端强制弹窗改密码
  res.json({
    token,
    mustChangePassword: !!admin.must_change_password,
    admin: {
      id: admin.id,
      username: admin.username,
      role: admin.role,
      mustChangePassword: !!admin.must_change_password
    }
  });
});

/**
 * POST /api/auth/change-password - 修改密码
 */
router.post('/change-password', authMiddleware, (req, res) => {
  const { oldPassword, newPassword } = req.body;
  if (!oldPassword || !newPassword) {
    return res.status(400).json({ error: '新旧密码不能为空' });
  }
  if (newPassword.length < 6) {
    return res.status(400).json({ error: '新密码至少6位' });
  }

  const admin = db.prepare('SELECT * FROM admins WHERE id = ?').get(req.admin.id);
  if (!bcrypt.compareSync(oldPassword, admin.password_hash)) {
    return res.status(400).json({ error: '原密码错误' });
  }

  const hash = bcrypt.hashSync(newPassword, 10);
  db.prepare('UPDATE admins SET password_hash = ?, must_change_password = 0 WHERE id = ?').run(hash, req.admin.id);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, detail, created_at) VALUES (?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'CHANGE_PASSWORD', '修改密码（must_change_password 已清除）', Date.now());

  res.json({ message: '密码修改成功' });
});

/**
 * GET /api/auth/me - 获取当前管理员信息
 */
router.get('/me', authMiddleware, (req, res) => {
  const admin = db.prepare('SELECT id, username, role, must_change_password, created_at, last_login FROM admins WHERE id = ?').get(req.admin.id);
  res.json({
    id: admin.id,
    username: admin.username,
    role: admin.role,
    mustChangePassword: !!admin.must_change_password,
    createdAt: admin.created_at,
    lastLogin: admin.last_login
  });
});

module.exports = router;
