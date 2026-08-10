const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/database');
const { authMiddleware } = require('../middleware/auth');

const router = express.Router();

// 所有接口需要登录
router.use(authMiddleware);

/**
 * GET /api/users - 用户列表（分页+搜索）
 */
router.get('/', (req, res) => {
  const page = parseInt(req.query.page) || 1;
  const size = parseInt(req.query.size) || 20;
  const search = req.query.search || '';
  const offset = (page - 1) * size;

  let whereClause = '';
  const params = [];
  if (search) {
    whereClause = 'WHERE u.phone LIKE ? OR u.nickname LIKE ?';
    params.push(`%${search}%`, `%${search}%`);
  }

  const countSql = `SELECT COUNT(*) as total FROM users u ${whereClause}`;
  const total = db.prepare(countSql).get(...params).total;

  const dataSql = `
    SELECT u.phone, u.nickname, u.is_admin, u.created_at, u.updated_at,
           q.plan_type, q.remaining_queries, q.monthly_expire_at, q.updated_at as quota_updated_at
    FROM users u
    LEFT JOIN quotas q ON u.phone = q.user_phone
    ${whereClause}
    ORDER BY u.created_at DESC
    LIMIT ? OFFSET ?
  `;
  const users = db.prepare(dataSql).all(...params, size, offset);

  res.json({
    total,
    page,
    size,
    totalPages: Math.ceil(total / size),
    data: users
  });
});

/**
 * GET /api/users/:phone - 用户详情
 */
router.get('/:phone', (req, res) => {
  const user = db.prepare(`
    SELECT u.phone, u.nickname, u.is_admin, u.created_at, u.updated_at,
           q.plan_type, q.remaining_queries, q.monthly_expire_at, q.server_version, q.updated_at as quota_updated_at
    FROM users u
    LEFT JOIN quotas q ON u.phone = q.user_phone
    WHERE u.phone = ?
  `).get(req.params.phone);

  if (!user) {
    return res.status(404).json({ error: '用户不存在' });
  }

  // 查询该用户的同步记录
  const syncs = db.prepare(
    'SELECT * FROM pending_sync WHERE user_phone = ? ORDER BY created_at DESC LIMIT 50'
  ).all(req.params.phone);

  res.json({ user, syncs });
});

/**
 * PUT /api/users/:phone/quota - 设置用户配额
 */
router.put('/:phone/quota', (req, res) => {
  const { phone } = req.params;
  const { planType, remainingQueries, monthlyExpireAt } = req.body;

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    return res.status(404).json({ error: '用户不存在' });
  }

  const now = Date.now();
  const existing = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);

  if (existing) {
    db.prepare(`
      UPDATE quotas SET plan_type = ?, remaining_queries = ?, monthly_expire_at = ?,
        server_version = server_version + 1, updated_at = ?
      WHERE user_phone = ?
    `).run(
      planType || existing.plan_type,
      remainingQueries ?? existing.remaining_queries,
      monthlyExpireAt !== undefined ? monthlyExpireAt : existing.monthly_expire_at,
      now,
      phone
    );
  } else {
    db.prepare(`
      INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, server_version, local_version, updated_at)
      VALUES (?, ?, ?, ?, 1, 0, ?)
    `).run(phone, planType || 'PAY_PER_USE', remainingQueries ?? 0, monthlyExpireAt || null, now);
  }

  // 记录日志
  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'SET_QUOTA', phone,
    JSON.stringify({ planType, remainingQueries, monthlyExpireAt }), now);

  res.json({ message: '配额设置成功' });
});

/**
 * POST /api/users/:phone/reset-password - 重置用户密码
 */
router.post('/:phone/reset-password', (req, res) => {
  const { phone } = req.params;
  const { newPassword } = req.body;

  if (!newPassword || newPassword.length < 6) {
    return res.status(400).json({ error: '密码至少6位' });
  }

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    return res.status(404).json({ error: '用户不存在' });
  }

  const hash = bcrypt.hashSync(newPassword, 10);
  db.prepare('UPDATE users SET password_hash = ?, updated_at = ? WHERE phone = ?')
    .run(hash, Date.now(), phone);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'RESET_PASSWORD', phone, '重置用户密码', Date.now());

  res.json({ message: '密码重置成功' });
});

/**
 * GET /api/users/stats/overview - 用户统计概览
 */
router.get('/stats/overview', (req, res) => {
  const totalUsers = db.prepare('SELECT COUNT(*) as count FROM users').get().count;
  const todayUsers = db.prepare(
    'SELECT COUNT(*) as count FROM users WHERE created_at >= ?'
  ).get(new Date().setHours(0, 0, 0, 0)).count;

  const quotaStats = db.prepare(`
    SELECT
      COUNT(*) as total_with_quota,
      SUM(CASE WHEN plan_type = 'PAY_PER_USE' THEN 1 ELSE 0 END) as pay_per_use,
      SUM(CASE WHEN plan_type = 'MONTHLY' THEN 1 ELSE 0 END) as monthly,
      SUM(remaining_queries) as total_remaining
    FROM quotas
  `).get();

  res.json({
    totalUsers,
    todayNewUsers: todayUsers,
    ...quotaStats
  });
});

module.exports = router;