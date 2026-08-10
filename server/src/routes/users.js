const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/database');
const { authMiddleware } = require('../middleware/auth');

const router = express.Router();

// ==================== 客户端公开接口（无需登录） ====================

/**
 * POST /api/users/client/register - 客户端用户注册
 * Body: { phone, password, nickname? }
 * 返回: { phone, planType, remainingQueries, monthlyExpireAt }
 */
router.post('/client/register', (req, res) => {
  const { phone, password, nickname } = req.body;
  if (!phone || !password) {
    return res.status(400).json({ error: '手机号和密码不能为空' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: '密码至少6位' });
  }

  const existing = db.prepare('SELECT phone FROM users WHERE phone = ?').get(phone);
  if (existing) {
    return res.status(409).json({ error: '该手机号已注册' });
  }

  const now = Date.now();
  const hash = bcrypt.hashSync(password, 10);

  // 创建用户
  db.prepare(
    'INSERT INTO users (phone, password_hash, nickname, is_admin, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?)'
  ).run(phone, hash, nickname || null, now, now);

  // 新用户默认赠送免费次数
  const freeQuota = parseInt(
    (db.prepare("SELECT value FROM system_config WHERE key = 'free_quota'").get()?.value) || '10'
  );
  db.prepare(
    'INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, server_version, local_version, updated_at) VALUES (?, ?, ?, NULL, 1, 0, ?)'
  ).run(phone, 'PAY_PER_USE', freeQuota, now);

  // 记录日志
  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(null, 'system', 'USER_REGISTER', phone, `客户端注册，赠送${freeQuota}次`, now);

  res.json({
    phone,
    planType: 'PAY_PER_USE',
    remainingQueries: freeQuota,
    monthlyExpireAt: null
  });
});

/**
 * POST /api/users/client/login - 客户端用户登录
 * Body: { phone, password }
 * 返回: { phone, nickname, planType, remainingQueries, monthlyExpireAt }
 */
router.post('/client/login', (req, res) => {
  const { phone, password } = req.body;
  if (!phone || !password) {
    return res.status(400).json({ error: '手机号和密码不能为空' });
  }

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    return res.status(401).json({ error: '手机号未注册' });
  }
  if (!bcrypt.compareSync(password, user.password_hash)) {
    return res.status(401).json({ error: '密码错误' });
  }

  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  db.prepare('UPDATE users SET updated_at = ? WHERE phone = ?').run(Date.now(), phone);

  res.json({
    phone: user.phone,
    nickname: user.nickname,
    planType: quota?.plan_type || 'PAY_PER_USE',
    remainingQueries: quota?.remaining_queries || 0,
    monthlyExpireAt: quota?.monthly_expire_at || null
  });
});

/**
 * POST /api/users/client/consume - 客户端消耗查询次数
 * Body: { phone, count? }
 * 返回: { success, remainingQueries }
 */
router.post('/client/consume', (req, res) => {
  const { phone, count } = req.body;
  if (!phone) {
    return res.status(400).json({ error: '手机号不能为空' });
  }

  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  if (!quota) {
    return res.status(404).json({ error: '用户配额不存在' });
  }

  const consumeCount = count || 1;
  const now = Date.now();

  // 月租用户：不扣次数，检查是否过期
  if (quota.plan_type === 'MONTHLY') {
    if (quota.monthly_expire_at && quota.monthly_expire_at < now) {
      return res.status(403).json({ error: '月租已过期，请联系管理员续费' });
    }
    // 记录消耗日志
    db.prepare(
      'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
    ).run(null, 'system', 'QUERY_CONSUME', phone, `月租用户查询，不扣次数`, now);
    return res.json({ success: true, remainingQueries: quota.remaining_queries });
  }

  // 按次用户：扣减次数
  if (quota.remaining_queries < consumeCount) {
    return res.status(403).json({ error: '查询次数不足，请联系管理员充值' });
  }

  db.prepare(
    'UPDATE quotas SET remaining_queries = remaining_queries - ?, server_version = server_version + 1, updated_at = ? WHERE user_phone = ?'
  ).run(consumeCount, now, phone);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(null, 'system', 'QUERY_CONSUME', phone, `消耗${consumeCount}次查询`, now);

  const updated = db.prepare('SELECT remaining_queries FROM quotas WHERE user_phone = ?').get(phone);
  res.json({ success: true, remainingQueries: updated.remaining_queries });
});

// ==================== 管理端接口（需要登录） ====================
router.use(authMiddleware);

/**
 * POST /api/users/register - 管理员注册用户
 * Body: { phone, password, nickname?, planType?, remainingQueries?, monthlyExpireAt? }
 */
router.post('/register', (req, res) => {
  const { phone, password, nickname, planType, remainingQueries, monthlyExpireAt } = req.body;
  if (!phone || !password) {
    return res.status(400).json({ error: '手机号和密码不能为空' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: '密码至少6位' });
  }

  const existing = db.prepare('SELECT phone FROM users WHERE phone = ?').get(phone);
  if (existing) {
    return res.status(409).json({ error: '该手机号已注册' });
  }

  const now = Date.now();
  const hash = bcrypt.hashSync(password, 10);

  db.prepare(
    'INSERT INTO users (phone, password_hash, nickname, is_admin, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?)'
  ).run(phone, hash, nickname || null, now, now);

  const pt = planType || 'PAY_PER_USE';
  const q = remainingQueries ?? (pt === 'MONTHLY' ? 99999 : 10);
  const expireAt = monthlyExpireAt || (pt === 'MONTHLY' ? now + 365 * 24 * 60 * 60 * 1000 : null);

  db.prepare(
    'INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, server_version, local_version, updated_at) VALUES (?, ?, ?, ?, 1, 0, ?)'
  ).run(phone, pt, q, expireAt, now);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'USER_REGISTER', phone,
    JSON.stringify({ planType: pt, remainingQueries: q, monthlyExpireAt: expireAt }), now);

  res.json({
    phone,
    planType: pt,
    remainingQueries: q,
    monthlyExpireAt: expireAt
  });
});

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