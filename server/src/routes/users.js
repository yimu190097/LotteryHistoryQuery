const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/database');
const { authMiddleware, generateUserToken, userAuthMiddleware } = require('../middleware/auth');

const router = express.Router();

// 手机号格式校验（中国大陆手机号）
const PHONE_REGEX = /^1[3-9]\d{9}$/;
function isValidPhone(phone) {
  return PHONE_REGEX.test(phone);
}

// 有效的套餐类型
const VALID_PLAN_TYPES = ['PAY_PER_USE', 'MONTHLY'];

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
  if (!isValidPhone(phone)) {
    return res.status(400).json({ error: '手机号格式不正确' });
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

  const freeQuota = parseInt(
    (db.prepare("SELECT value FROM system_config WHERE key = 'free_quota'").get()?.value) || '10'
  );
  db.prepare(
    'INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, server_version, local_version, updated_at) VALUES (?, ?, ?, NULL, 1, 0, ?)'
  ).run(phone, 'PAY_PER_USE', freeQuota, now);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(null, 'system', 'USER_REGISTER', phone, `客户端注册，赠送${freeQuota}次`, now);

  const token = generateUserToken({ phone, nickname: nickname || null });
  res.json({
    token,
    phone,
    nickname: nickname || null,
    planType: 'PAY_PER_USE',
    remainingQueries: freeQuota,
    monthlyExpireAt: null
  });
});

/**
 * POST /api/users/client/login - 客户端用户登录
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

  const token = generateUserToken(user);
  res.json({
    token,
    phone: user.phone,
    nickname: user.nickname,
    planType: quota?.plan_type || 'PAY_PER_USE',
    remainingQueries: quota?.remaining_queries || 0,
    monthlyExpireAt: quota?.monthly_expire_at || null
  });
});

/**
 * POST /api/users/client/consume - 客户端消耗查询次数（需要用户 Token）
 *
 * 幂等性：若 Body 携带 clientOpId，则相同 clientOpId 重复调用只扣一次，
 * 后续重试直接返回首次结果。覆盖「服务器已扣减但响应丢失 → 客户端重试」场景。
 */
router.post('/client/consume', userAuthMiddleware, (req, res) => {
  const { phone, count, clientOpId } = req.body || {};
  if (!phone) {
    return res.status(400).json({ error: '手机号不能为空' });
  }
  // 防 IDOR：只能为自己扣次数
  if (phone !== req.user.phone) {
    return res.status(403).json({ error: '不能操作其他用户的配额' });
  }

  const now = Date.now();

  // 幂等检查：相同 clientOpId 直接复用首次结果
  if (clientOpId) {
    const cached = db.prepare('SELECT result_payload FROM idempotency_log WHERE op_id = ?').get(clientOpId);
    if (cached) {
      if (cached.result_payload) {
        return res.json(JSON.parse(cached.result_payload));
      }
      // 极少数情况：占位行存在但结果未写入（异常崩溃），返回当前配额快照
      const q = db.prepare('SELECT remaining_queries FROM quotas WHERE user_phone = ?').get(phone);
      return res.json({ success: true, remainingQueries: q?.remaining_queries ?? 0 });
    }
  }

  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  if (!quota) {
    return res.status(404).json({ error: '用户配额不存在' });
  }

  const consumeCount = count || 1;

  // 占位 clientOpId（如有），后续无论扣减是否成功都写入结果，防止并发重复入账
  const tx = db.transaction(() => {
    if (clientOpId) {
      const ins = db.prepare(
        'INSERT OR IGNORE INTO idempotency_log (op_id, user_phone, action, result_payload, created_at) VALUES (?, ?, ?, ?, ?)'
      ).run(clientOpId, phone, 'QUERY_CONSUME', '', now);
      if (ins.changes === 0) {
        // 并发情况下另一请求已占用 op_id，回退读快照
        const c = db.prepare('SELECT result_payload FROM idempotency_log WHERE op_id = ?').get(clientOpId);
        if (c && c.result_payload) return JSON.parse(c.result_payload);
        const q = db.prepare('SELECT remaining_queries FROM quotas WHERE user_phone = ?').get(phone);
        return { success: true, remainingQueries: q?.remaining_queries ?? 0 };
      }
    }

    if (quota.plan_type === 'MONTHLY') {
      if (quota.monthly_expire_at && quota.monthly_expire_at < now) {
        const err = { error: '月租已过期，请联系管理员续费' };
        if (clientOpId) {
          db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?')
            .run(JSON.stringify(err), clientOpId);
        }
        return err;
      }
      db.prepare(
        'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
      ).run(null, 'system', 'QUERY_CONSUME', phone, '月租用户查询，不扣次数', now);
      const result = { success: true, remainingQueries: quota.remaining_queries };
      if (clientOpId) {
        db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?')
          .run(JSON.stringify(result), clientOpId);
      }
      return result;
    }

    if (quota.remaining_queries < consumeCount) {
      const err = { error: '查询次数不足，请联系管理员充值' };
      if (clientOpId) {
        db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?')
          .run(JSON.stringify(err), clientOpId);
      }
      return err;
    }

    db.prepare(
      'UPDATE quotas SET remaining_queries = remaining_queries - ?, server_version = server_version + 1, updated_at = ? WHERE user_phone = ?'
    ).run(consumeCount, now, phone);

    db.prepare(
      'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
    ).run(null, 'system', 'QUERY_CONSUME', phone, `消耗${consumeCount}次查询`, now);

    const updated = db.prepare('SELECT remaining_queries FROM quotas WHERE user_phone = ?').get(phone);
    const result = { success: true, remainingQueries: updated.remaining_queries };
    if (clientOpId) {
      db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?')
        .run(JSON.stringify(result), clientOpId);
    }
    return result;
  });

  let result;
  try {
    result = tx();
  } catch (e) {
    console.error('[consume] tx failed:', e.message);
    return res.status(500).json({ error: '扣减失败，请稍后重试' });
  }

  if (result.error) {
    return res.status(403).json(result);
  }
  res.json(result);
});

/**
 * GET /api/users/client/config - 客户端公开配置（免登录）
 * 返回: { free_quota }
 */
router.get('/client/config', (req, res) => {
  const row = db.prepare("SELECT value FROM system_config WHERE key = 'free_quota'").get();
  const freeQuota = parseInt(row?.value) || 10;
  res.json({ free_quota: freeQuota });
});

/**
 * GET /api/users/client/quota - 获取当前用户配额（需要用户 Token）
 * 返回: { phone, nickname, planType, remainingQueries, monthlyExpireAt }
 */
router.get('/client/quota', userAuthMiddleware, (req, res) => {
  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(req.user.phone);
  const user = db.prepare('SELECT phone, nickname FROM users WHERE phone = ?').get(req.user.phone);
  const now = Date.now();

  // 月租用户检查是否过期
  let planType = quota?.plan_type || 'PAY_PER_USE';
  let remainingQueries = quota?.remaining_queries || 0;
  let monthlyExpireAt = quota?.monthly_expire_at || null;

  if (planType === 'MONTHLY' && monthlyExpireAt && monthlyExpireAt < now) {
    // 月租已过期，降级为按次用户
    planType = 'PAY_PER_USE';
    remainingQueries = 0;
    monthlyExpireAt = null;
  }

  res.json({
    phone: user?.phone || req.user.phone,
    nickname: user?.nickname || req.user.nickname,
    planType,
    remainingQueries,
    monthlyExpireAt
  });
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
  if (!isValidPhone(phone)) {
    return res.status(400).json({ error: '手机号格式不正确' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: '密码至少6位' });
  }

  const pt = planType || 'PAY_PER_USE';
  if (!VALID_PLAN_TYPES.includes(pt)) {
    return res.status(400).json({ error: '无效的套餐类型' });
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
 * GET /api/users - 用户列表（分页+搜索+套餐筛选）
 * Query: page, size, search, planType
 */
router.get('/', (req, res) => {
  const page = Math.max(1, parseInt(req.query.page) || 1);
  const size = Math.min(100, Math.max(1, parseInt(req.query.size) || 20));
  const search = req.query.search || '';
  const planType = req.query.planType || '';
  const offset = (page - 1) * size;

  const conditions = [];
  const params = [];

  if (search) {
    conditions.push('(u.phone LIKE ? OR u.nickname LIKE ?)');
    params.push(`%${search}%`, `%${search}%`);
  }
  if (planType && VALID_PLAN_TYPES.includes(planType)) {
    conditions.push('q.plan_type = ?');
    params.push(planType);
  }

  const whereClause = conditions.length > 0 ? 'WHERE ' + conditions.join(' AND ') : '';

  const countSql = `SELECT COUNT(*) as total FROM users u LEFT JOIN quotas q ON u.phone = q.user_phone ${whereClause}`;
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
 * Body: { planType?, remainingQueries?, monthlyExpireAt? }
 */
router.put('/:phone/quota', (req, res) => {
  const { phone } = req.params;
  const { planType, remainingQueries, monthlyExpireAt } = req.body;

  if (planType && !VALID_PLAN_TYPES.includes(planType)) {
    return res.status(400).json({ error: '无效的套餐类型' });
  }

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
 * POST /api/users/:phone/quota/add - 给用户增加查询次数
 * Body: { count }
 */
router.post('/:phone/quota/add', (req, res) => {
  const { phone } = req.params;
  const { count } = req.body;
  const addCount = parseInt(count);
  if (!addCount || addCount <= 0) {
    return res.status(400).json({ error: '次数必须为正整数' });
  }

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) return res.status(404).json({ error: '用户不存在' });

  const now = Date.now();
  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  if (!quota) return res.status(404).json({ error: '配额不存在，请先初始化' });

  db.prepare(`
    UPDATE quotas SET remaining_queries = remaining_queries + ?, server_version = server_version + 1, updated_at = ?
    WHERE user_phone = ?
  `).run(addCount, now, phone);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'QUOTA_ADD', phone, `增加 ${addCount} 次`, now);

  const updated = db.prepare('SELECT remaining_queries FROM quotas WHERE user_phone = ?').get(phone);
  res.json({ message: '已增加次数', remainingQueries: updated.remaining_queries });
});

/**
 * POST /api/users/:phone/quota/plan - 开通月租/年租套餐
 * Body: { planType: "MONTHLY", days: 30 }
 */
router.post('/:phone/quota/plan', (req, res) => {
  const { phone } = req.params;
  const { planType, days } = req.body;
  const dayCount = parseInt(days) || 30;

  if (planType !== 'MONTHLY') {
    return res.status(400).json({ error: '目前仅支持 MONTHLY 套餐' });
  }
  if (dayCount <= 0) return res.status(400).json({ error: '天数必须为正' });

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) return res.status(404).json({ error: '用户不存在' });

  const now = Date.now();
  const expireAt = now + dayCount * 24 * 60 * 60 * 1000;
  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);

  if (quota) {
    db.prepare(`
      UPDATE quotas SET plan_type = 'MONTHLY', monthly_expire_at = ?,
        server_version = server_version + 1, updated_at = ?
      WHERE user_phone = ?
    `).run(expireAt, now, phone);
  } else {
    db.prepare(`
      INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, server_version, local_version, updated_at)
      VALUES (?, 'MONTHLY', 99999, ?, 1, 0, ?)
    `).run(phone, expireAt, now);
  }

  const label = dayCount >= 365 ? `${dayCount}天（年租）` : `${dayCount}天`;
  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'QUOTA_PLAN', phone, `开通月租 ${label}`, now);

  res.json({ message: `已开通月租 ${dayCount} 天`, monthlyExpireAt: expireAt });
});

/**
 * POST /api/users/:phone/reset-password - 重置用户密码
 * Body: { newPassword }
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
 * DELETE /api/users/:phone - 删除用户（超级管理员）
 */
router.delete('/:phone', (req, res) => {
  const { phone } = req.params;

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) {
    return res.status(404).json({ error: '用户不存在' });
  }
  if (user.is_admin) {
    return res.status(403).json({ error: '不能删除管理员账号' });
  }

  const now = Date.now();

  // 删除关联数据
  db.prepare('DELETE FROM quotas WHERE user_phone = ?').run(phone);
  db.prepare('DELETE FROM pending_sync WHERE user_phone = ?').run(phone);
  db.prepare('DELETE FROM users WHERE phone = ?').run(phone);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'DELETE_USER', phone, '删除用户及关联数据', now);

  res.json({ message: '用户已删除' });
});

/**
 * GET /api/users/stats/overview - 用户统计概览
 */
router.get('/stats/overview', (req, res) => {
  const now = Date.now();
  const totalUsers = db.prepare('SELECT COUNT(*) as count FROM users').get().count;
  const todayUsers = db.prepare(
    'SELECT COUNT(*) as count FROM users WHERE created_at >= ?'
  ).get(new Date().setHours(0, 0, 0, 0)).count;

  const quotaStats = db.prepare(`
    SELECT
      COUNT(*) as total_with_quota,
      SUM(CASE WHEN plan_type = 'PAY_PER_USE' THEN 1 ELSE 0 END) as pay_per_use,
      SUM(CASE WHEN plan_type = 'MONTHLY' THEN 1 ELSE 0 END) as monthly,
      SUM(CASE WHEN plan_type = 'MONTHLY' AND monthly_expire_at < ? THEN 1 ELSE 0 END) as expired_monthly,
      SUM(remaining_queries) as total_remaining
    FROM quotas
  `).get(now);

  res.json({
    totalUsers,
    todayNewUsers: todayUsers,
    ...quotaStats
  });
});

module.exports = router;