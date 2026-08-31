const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/database');
const { authMiddleware, generateUserToken, userAuthMiddleware, MAX_SESSIONS_PER_USER } = require('../middleware/auth');

const router = express.Router();

// 手机号格式校验（中国大陆手机号）
const PHONE_REGEX = /^1[3-9]\d{9}$/;
function isValidPhone(phone) {
  return PHONE_REGEX.test(phone);
}

// 有效的套餐类型
const VALID_PLAN_TYPES = ['FREE', 'MONTHLY_VIP', 'QUARTERLY_VIP', 'SEMI_ANNUAL_VIP', 'ANNUAL_VIP'];
const VIP_TYPES = ['MONTHLY_VIP', 'QUARTERLY_VIP', 'SEMI_ANNUAL_VIP', 'ANNUAL_VIP'];

// 获取当天日期（epoch day，用于每日重置免费次数）
function todayEpochDay() {
  return Math.floor(Date.now() / 86400000);
}

// 获取免费次数上限
function getFreeQueryLimit() {
  const row = db.prepare("SELECT value FROM system_config WHERE key = 'free_query_limit'").get();
  return parseInt(row?.value) || 2;
}

// 判断是否可查询，返回 { canQuery, vipExpired, freeUsed, freeLimit, planType }
function checkCanQuery(phone) {
  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  if (!quota) return { canQuery: false, vipExpired: false, freeUsed: 0, freeLimit: getFreeQueryLimit(), planType: 'FREE' };

  const now = Date.now();
  const today = todayEpochDay();
  const isVip = VIP_TYPES.includes(quota.plan_type);
  const freeLimit = getFreeQueryLimit();

  // VIP 且未过期
  if (isVip && quota.monthly_expire_at && quota.monthly_expire_at > now) {
    return { canQuery: true, vipExpired: false, freeUsed: 0, freeLimit, planType: quota.plan_type };
  }

  // VIP 已过期 → 降级为免费用户
  if (isVip && quota.monthly_expire_at && quota.monthly_expire_at <= now) {
    return { canQuery: false, vipExpired: true, freeUsed: 0, freeLimit, planType: 'FREE' };
  }

  // 免费用户：检查每日额度
  let freeUsed = quota.remaining_queries;
  if (quota.free_query_date !== today) {
    freeUsed = 0; // 新的一天，重置
  }
  return {
    canQuery: freeUsed < freeLimit,
    vipExpired: false,
    freeUsed,
    freeLimit,
    planType: quota.plan_type
  };
}

// 终端管理：创建 session，超过上限则踢掉最旧的
// 返回 { kicked: true/false } 表示是否踢掉了终端
function createSession(phone, jti, req) {
  const now = Date.now();
  const deviceInfo = req.headers['user-agent'] || 'Unknown';
  const ip = req.ip || req.socket?.remoteAddress || '';

  let kicked = false;
  const count = db.prepare('SELECT COUNT(*) as cnt FROM user_sessions WHERE user_phone = ?').get(phone).cnt;
  if (count >= MAX_SESSIONS_PER_USER) {
    const oldest = db.prepare(
      'SELECT id FROM user_sessions WHERE user_phone = ? ORDER BY created_at ASC LIMIT 1'
    ).get(phone);
    if (oldest) {
      db.prepare('DELETE FROM user_sessions WHERE id = ?').run(oldest.id);
      kicked = true;
    }
  }

  db.prepare(
    'INSERT INTO user_sessions (user_phone, jti, device_info, ip_address, created_at, last_active_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(phone, jti, deviceInfo, ip, now, now);

  return { kicked };
}

// 更新 session 活跃时间
function touchSession(phone, jti) {
  if (!jti) return;
  db.prepare('UPDATE user_sessions SET last_active_at = ? WHERE user_phone = ? AND jti = ?')
    .run(Date.now(), phone, jti);
}

// ==================== 客户端公开接口（无需登录） ====================

/**
 * POST /api/users/client/register - 客户端用户注册
 * 新用户默认 FREE 套餐，每日 2 次免费查询
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
  if (password.length > 64) {
    return res.status(400).json({ error: '密码过长（最多64位）' });
  }
  if (nickname && nickname.length > 50) {
    return res.status(400).json({ error: '昵称过长（最多50字）' });
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

  const freeLimit = getFreeQueryLimit();
  db.prepare(
    'INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, free_query_date, server_version, local_version, updated_at) VALUES (?, ?, ?, NULL, ?, 1, 0, ?)'
  ).run(phone, 'FREE', 0, todayEpochDay(), now);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(null, 'system', 'USER_REGISTER', phone, `客户端注册，每日${freeLimit}次免费查询`, now);

  const { token, jti } = generateUserToken({ phone, nickname: nickname || null });
  const session = createSession(phone, jti, req);
  const resp = {
    token,
    phone,
    nickname: nickname || null,
    planType: 'FREE',
    freeUsed: 0,
    freeLimit,
    monthlyExpireAt: null,
    vipExpired: false
  };
  if (session.kicked) resp.notice = `已达到最大终端数限制（${MAX_SESSIONS_PER_USER}台），已自动踢掉最旧的终端`;
  res.json(resp);
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

  db.prepare('UPDATE users SET updated_at = ? WHERE phone = ?').run(Date.now(), phone);

  const token = generateUserToken(user);
  const session = createSession(user.phone, token.jti, req);

  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  const now = Date.now();
  const today = todayEpochDay();
  const freeLimit = getFreeQueryLimit();
  const isVip = quota && VIP_TYPES.includes(quota.plan_type);

  // 判断 VIP 状态
  let planType = 'FREE';
  let monthlyExpireAt = null;
  let freeUsed = 0;
  let vipExpired = false;

  if (isVip && quota.monthly_expire_at && quota.monthly_expire_at > now) {
    planType = quota.plan_type;
    monthlyExpireAt = quota.monthly_expire_at;
  } else if (isVip) {
    vipExpired = true;
    // VIP 过期降级，重置当天免费计数
    if (quota.free_query_date !== today) {
      freeUsed = 0;
    } else {
      freeUsed = quota.remaining_queries;
    }
  } else {
    // 免费用户
    if (quota && quota.free_query_date === today) {
      freeUsed = quota.remaining_queries;
    }
  }

  const resp = {
    token: token.token,
    phone: user.phone,
    nickname: user.nickname,
    planType,
    freeUsed,
    freeLimit,
    monthlyExpireAt,
    vipExpired
  };
  if (session.kicked) resp.notice = `已达到最大终端数限制（${MAX_SESSIONS_PER_USER}台），已自动踢掉最旧的终端`;
  res.json(resp);
});

/**
 * POST /api/users/client/consume - 客户端消耗查询次数（需要用户 Token）
 * VIP 用户不限次数；免费用户每日 2 次，隔天自动重置
 * 幂等性：clientOpId 防止重复扣减
 */
router.post('/client/consume', userAuthMiddleware, (req, res) => {
  const { phone, count, clientOpId } = req.body || {};
  if (!phone) {
    return res.status(400).json({ error: '手机号不能为空' });
  }
  if (phone !== req.user.phone) {
    return res.status(403).json({ error: '不能操作其他用户的配额' });
  }

  touchSession(phone, req.user.jti);

  const now = Date.now();
  const today = todayEpochDay();
  const freeLimit = getFreeQueryLimit();

  // 幂等检查
  if (clientOpId) {
    const cached = db.prepare('SELECT result_payload FROM idempotency_log WHERE op_id = ?').get(clientOpId);
    if (cached) {
      if (cached.result_payload) return res.json(JSON.parse(cached.result_payload));
      const q = checkCanQuery(phone);
      const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
      return res.json({ success: true, canQuery: q.canQuery, freeUsed: q.freeUsed, freeLimit: q.freeLimit, planType: q.planType, monthlyExpireAt: quota?.monthly_expire_at || null });
    }
  }

  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);
  if (!quota) {
    return res.status(404).json({ error: '用户配额不存在' });
  }

  const isVip = VIP_TYPES.includes(quota.plan_type);
  const consumeCount = count || 1;

  const tx = db.transaction(() => {
    if (clientOpId) {
      const ins = db.prepare(
        'INSERT OR IGNORE INTO idempotency_log (op_id, user_phone, action, result_payload, created_at) VALUES (?, ?, ?, ?, ?)'
      ).run(clientOpId, phone, 'QUERY_CONSUME', '', now);
      if (ins.changes === 0) {
        const c = db.prepare('SELECT result_payload FROM idempotency_log WHERE op_id = ?').get(clientOpId);
        if (c && c.result_payload) return JSON.parse(c.result_payload);
        return { success: true, canQuery: true, freeUsed: 0, freeLimit, planType: quota.plan_type, monthlyExpireAt: quota.monthly_expire_at || null };
      }
    }

    // VIP 且未过期 → 不限次数
    if (isVip && quota.monthly_expire_at && quota.monthly_expire_at > now) {
      const result = { success: true, canQuery: true, freeUsed: 0, freeLimit, planType: quota.plan_type, monthlyExpireAt: quota.monthly_expire_at };
      if (clientOpId) {
        db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?').run(JSON.stringify(result), clientOpId);
      }
      db.prepare(
        'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
      ).run(null, 'system', 'QUERY_CONSUME', phone, `VIP用户查询（${quota.plan_type}）`, now);
      return result;
    }

    // VIP 已过期 → 降级检查免费额度
    if (isVip) {
      db.prepare('UPDATE quotas SET plan_type = ?, remaining_queries = ?, free_query_date = ?, updated_at = ? WHERE user_phone = ?')
        .run('FREE', 0, today, now, phone);
    }

    // 免费用户：每日重置
    let freeUsed = quota.remaining_queries;
    if (quota.free_query_date !== today) {
      freeUsed = 0;
    }

    if (freeUsed + consumeCount > freeLimit) {
      const err = { error: `今日免费查询次数已用完（${freeLimit}次/天），请开通VIP获取不限次数`, freeUsed, freeLimit };
      if (clientOpId) {
        db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?').run(JSON.stringify(err), clientOpId);
      }
      return err;
    }

    freeUsed += consumeCount;
    db.prepare(
      'UPDATE quotas SET remaining_queries = ?, free_query_date = ?, server_version = server_version + 1, updated_at = ? WHERE user_phone = ?'
    ).run(freeUsed, today, now, phone);

    db.prepare(
      'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
    ).run(null, 'system', 'QUERY_CONSUME', phone, `免费查询 ${consumeCount} 次（今日已用 ${freeUsed}/${freeLimit}）`, now);

    const result = { success: true, canQuery: freeUsed < freeLimit, freeUsed, freeLimit, planType: 'FREE' };
    if (clientOpId) {
      db.prepare('UPDATE idempotency_log SET result_payload = ? WHERE op_id = ?').run(JSON.stringify(result), clientOpId);
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
 * 返回: { free_query_limit }
 */
router.get('/client/config', (req, res) => {
  const freeLimit = getFreeQueryLimit();
  // 同时返回 VIP 套餐列表
  const plans = db.prepare('SELECT plan_type, name, price, duration_days FROM vip_plans ORDER BY price ASC').all();
  res.json({ free_query_limit: freeLimit, vip_plans: plans });
});

/**
 * GET /api/users/client/quota - 获取当前用户配额（需要用户 Token）
 * 返回: { phone, nickname, planType, freeUsed, freeLimit, monthlyExpireAt, vipExpired }
 */
router.get('/client/quota', userAuthMiddleware, (req, res) => {
  const user = db.prepare('SELECT phone, nickname FROM users WHERE phone = ?').get(req.user.phone);
  const now = Date.now();
  const today = todayEpochDay();
  const freeLimit = getFreeQueryLimit();
  const quota = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(req.user.phone);

  let planType = 'FREE';
  let monthlyExpireAt = null;
  let freeUsed = 0;
  let vipExpired = false;

  if (quota) {
    const isVip = VIP_TYPES.includes(quota.plan_type);
    if (isVip && quota.monthly_expire_at && quota.monthly_expire_at > now) {
      planType = quota.plan_type;
      monthlyExpireAt = quota.monthly_expire_at;
    } else if (isVip) {
      vipExpired = true;
      freeUsed = (quota.free_query_date === today) ? quota.remaining_queries : 0;
    } else {
      freeUsed = (quota.free_query_date === today) ? quota.remaining_queries : 0;
    }
  }

  res.json({
    phone: user?.phone || req.user.phone,
    nickname: user?.nickname || req.user.nickname,
    planType,
    freeUsed,
    freeLimit,
    monthlyExpireAt,
    vipExpired
  });
});

// ==================== 客户端终端管理（需要用户 Token） ====================

/**
 * GET /api/users/client/sessions - 获取当前用户所有活跃终端
 * 返回: [{ id, deviceInfo, ipAddress, createdAt, lastActiveAt, isCurrent }]
 */
router.get('/client/sessions', userAuthMiddleware, (req, res) => {
  const sessions = db.prepare(
    'SELECT id, device_info, ip_address, created_at, last_active_at, jti FROM user_sessions WHERE user_phone = ? ORDER BY created_at DESC'
  ).all(req.user.phone);

  const now = Date.now();
  const result = sessions.map(s => ({
    id: s.id,
    deviceInfo: s.device_info,
    ipAddress: s.ip_address,
    createdAt: s.created_at,
    lastActiveAt: s.last_active_at,
    isCurrent: s.jti === req.user.jti,
    // 24h 内有活动视为"在线"
    online: (now - s.last_active_at) < 24 * 60 * 60 * 1000
  }));

  res.json({ sessions: result, maxSessions: MAX_SESSIONS_PER_USER });
});

/**
 * DELETE /api/users/client/sessions/:id - 删除指定终端
 */
router.delete('/client/sessions/:id', userAuthMiddleware, (req, res) => {
  const session = db.prepare(
    'SELECT id, jti FROM user_sessions WHERE id = ? AND user_phone = ?'
  ).get(req.params.id, req.user.phone);

  if (!session) {
    return res.status(404).json({ error: '终端不存在' });
  }

  db.prepare('DELETE FROM user_sessions WHERE id = ?').run(session.id);
  res.json({ message: '终端已删除' });
});

/**
 * DELETE /api/users/client/sessions - 删除所有其他终端（保留当前）
 */
router.delete('/client/sessions', userAuthMiddleware, (req, res) => {
  const result = db.prepare(
    'DELETE FROM user_sessions WHERE user_phone = ? AND jti != ?'
  ).run(req.user.phone, req.user.jti);

  res.json({ message: `已删除 ${result.changes} 个其他终端` });
});

// ==================== 管理端接口（需要登录） ====================
router.use(authMiddleware);

/**
 * POST /api/users/register - 管理员注册用户
 * Body: { phone, password, nickname?, planType? }
 */
router.post('/register', (req, res) => {
  const { phone, password, nickname, planType } = req.body;
  if (!phone || !password) {
    return res.status(400).json({ error: '手机号和密码不能为空' });
  }
  if (!isValidPhone(phone)) {
    return res.status(400).json({ error: '手机号格式不正确' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: '密码至少6位' });
  }
  if (password.length > 64) {
    return res.status(400).json({ error: '密码过长（最多64位）' });
  }

  const pt = planType || 'FREE';
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

  // 如果是 VIP，计算到期时间
  let expireAt = null;
  if (VIP_TYPES.includes(pt)) {
    const plan = db.prepare('SELECT duration_days FROM vip_plans WHERE plan_type = ?').get(pt);
    const days = plan?.duration_days || 30;
    expireAt = now + days * 24 * 60 * 60 * 1000;
  }

  db.prepare(
    'INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, free_query_date, server_version, local_version, updated_at) VALUES (?, ?, ?, ?, ?, 1, 0, ?)'
  ).run(phone, pt, 0, expireAt, todayEpochDay(), now);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'USER_REGISTER', phone,
    JSON.stringify({ planType: pt, monthlyExpireAt: expireAt }), now);

  res.json({ phone, planType: pt, monthlyExpireAt: expireAt });
});

/**
 * GET /api/users - 用户列表（分页+搜索+套餐筛选）
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
           q.plan_type, q.remaining_queries, q.monthly_expire_at, q.free_query_date, q.updated_at as quota_updated_at,
           (SELECT COUNT(*) FROM user_sessions WHERE user_phone = u.phone) as session_count
    FROM users u
    LEFT JOIN quotas q ON u.phone = q.user_phone
    ${whereClause}
    ORDER BY u.created_at DESC
    LIMIT ? OFFSET ?
  `;
  const users = db.prepare(dataSql).all(...params, size, offset);

  const now = Date.now();
  const today = todayEpochDay();
  const freeLimit = getFreeQueryLimit();

  const enriched = users.map(u => {
    const isVip = u.plan_type && VIP_TYPES.includes(u.plan_type);
    const vipActive = isVip && u.monthly_expire_at && u.monthly_expire_at > now;
    const vipExpired = isVip && !vipActive;
    const freeUsed = (u.free_query_date === today) ? u.remaining_queries : 0;
    return {
      ...u,
      planType: u.plan_type || 'FREE',
      vipActive,
      vipExpired,
      freeUsed,
      freeLimit,
      session_count: u.session_count || 0
    };
  });

  res.json({ total, page, size, totalPages: Math.ceil(total / size), data: enriched });
});

// ==================== VIP 套餐管理（管理端） ====================

/**
 * GET /api/users/vip/plans - 获取 VIP 套餐列表
 */
router.get('/vip/plans', (req, res) => {
  const plans = db.prepare('SELECT plan_type, name, price, duration_days, updated_at FROM vip_plans ORDER BY price ASC').all();
  res.json({ plans });
});

/**
 * PUT /api/users/vip/plans/:planType - 更新 VIP 套餐价格
 * Body: { price }
 */
router.put('/vip/plans/:planType', (req, res) => {
  const { planType } = req.params;
  const { price } = req.body;
  if (!price || parseFloat(price) < 0) {
    return res.status(400).json({ error: '价格无效' });
  }
  const plan = db.prepare('SELECT * FROM vip_plans WHERE plan_type = ?').get(planType);
  if (!plan) return res.status(404).json({ error: '套餐不存在' });

  db.prepare('UPDATE vip_plans SET price = ?, updated_at = ? WHERE plan_type = ?')
    .run(parseFloat(price), Date.now(), planType);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'VIP_PRICE_UPDATE', planType,
    `价格: ${plan.price} → ${price}`, Date.now());

  res.json({ message: '价格更新成功' });
});

// ==================== 用户详情 + 配额管理（管理端，:phone 路由放最后） ====================
router.get('/:phone', (req, res) => {
  const user = db.prepare(`
    SELECT u.phone, u.nickname, u.is_admin, u.created_at, u.updated_at,
           q.plan_type, q.remaining_queries, q.monthly_expire_at, q.free_query_date, q.server_version, q.updated_at as quota_updated_at
    FROM users u
    LEFT JOIN quotas q ON u.phone = q.user_phone
    WHERE u.phone = ?
  `).get(req.params.phone);

  if (!user) return res.status(404).json({ error: '用户不存在' });

  const now = Date.now();
  const today = todayEpochDay();
  const isVip = user.plan_type && VIP_TYPES.includes(user.plan_type);
  const vipActive = isVip && user.monthly_expire_at && user.monthly_expire_at > now;
  const freeUsed = (user.free_query_date === today) ? user.remaining_queries : 0;

  res.json({
    user: {
      ...user,
      planType: user.plan_type || 'FREE',
      vipActive,
      vipExpired: isVip && !vipActive,
      freeUsed,
      freeLimit: getFreeQueryLimit()
    }
  });
});

/**
 * PUT /api/users/:phone/quota - 设置用户套餐（开通 VIP 或降级 FREE）
 * Body: { planType }
 */
router.put('/:phone/quota', (req, res) => {
  const { phone } = req.params;
  const { planType } = req.body;

  if (!planType || !VALID_PLAN_TYPES.includes(planType)) {
    return res.status(400).json({ error: '无效的套餐类型' });
  }

  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(phone);
  if (!user) return res.status(404).json({ error: '用户不存在' });

  const now = Date.now();
  const existing = db.prepare('SELECT * FROM quotas WHERE user_phone = ?').get(phone);

  let expireAt = null;
  if (VIP_TYPES.includes(planType)) {
    const plan = db.prepare('SELECT duration_days FROM vip_plans WHERE plan_type = ?').get(planType);
    const days = plan?.duration_days || 30;
    expireAt = now + days * 24 * 60 * 60 * 1000;
  }

  if (existing) {
    db.prepare(`
      UPDATE quotas SET plan_type = ?, monthly_expire_at = ?, remaining_queries = 0, free_query_date = ?,
        server_version = server_version + 1, updated_at = ?
      WHERE user_phone = ?
    `).run(planType, expireAt, todayEpochDay(), now, phone);
  } else {
    db.prepare(`
      INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, free_query_date, server_version, local_version, updated_at)
      VALUES (?, ?, 0, ?, ?, 1, 0, ?)
    `).run(phone, planType, expireAt, todayEpochDay(), now);
  }

  const planName = db.prepare('SELECT name FROM vip_plans WHERE plan_type = ?').get(planType);
  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'SET_QUOTA', phone,
    JSON.stringify({ planType, name: planName?.name, monthlyExpireAt: expireAt }), now);

  res.json({ message: '套餐设置成功', planType, monthlyExpireAt: expireAt });
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
  if (newPassword.length > 64) {
    return res.status(400).json({ error: '密码过长（最多64位）' });
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
  db.prepare('DELETE FROM user_sessions WHERE user_phone = ?').run(phone);
  db.prepare('DELETE FROM users WHERE phone = ?').run(phone);

  db.prepare(
    'INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).run(req.admin.id, req.admin.username, 'DELETE_USER', phone, '删除用户及关联数据', now);

  res.json({ message: '用户已删除' });
});

/**
 * GET /api/users/:phone/sessions - 管理员查看用户终端列表
 */
router.get('/:phone/sessions', (req, res) => {
  const user = db.prepare('SELECT * FROM users WHERE phone = ?').get(req.params.phone);
  if (!user) return res.status(404).json({ error: '用户不存在' });

  const sessions = db.prepare(
    'SELECT id, device_info, ip_address, created_at, last_active_at FROM user_sessions WHERE user_phone = ? ORDER BY created_at DESC'
  ).all(req.params.phone);

  const now = Date.now();
  res.json({
    sessions: sessions.map(s => ({
      id: s.id,
      deviceInfo: s.device_info,
      ipAddress: s.ip_address,
      createdAt: s.created_at,
      lastActiveAt: s.last_active_at,
      online: (now - s.last_active_at) < 24 * 60 * 60 * 1000
    })),
    maxSessions: MAX_SESSIONS_PER_USER
  });
});

/**
 * DELETE /api/users/:phone/sessions/:id - 管理员删除用户终端
 */
router.delete('/:phone/sessions/:id', (req, res) => {
  const session = db.prepare(
    'SELECT id FROM user_sessions WHERE id = ? AND user_phone = ?'
  ).get(req.params.id, req.params.phone);

  if (!session) return res.status(404).json({ error: '终端不存在' });

  db.prepare('DELETE FROM user_sessions WHERE id = ?').run(session.id);
  res.json({ message: '终端已删除' });
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
      SUM(CASE WHEN plan_type IN ('MONTHLY_VIP','QUARTERLY_VIP','SEMI_ANNUAL_VIP','ANNUAL_VIP') AND monthly_expire_at > ? THEN 1 ELSE 0 END) as active_vip,
      SUM(CASE WHEN plan_type IN ('MONTHLY_VIP','QUARTERLY_VIP','SEMI_ANNUAL_VIP','ANNUAL_VIP') AND monthly_expire_at <= ? THEN 1 ELSE 0 END) as expired_vip,
      SUM(CASE WHEN plan_type = 'FREE' OR plan_type NOT IN ('MONTHLY_VIP','QUARTERLY_VIP','SEMI_ANNUAL_VIP','ANNUAL_VIP') THEN 1 ELSE 0 END) as free_users
    FROM quotas
  `).get(now, now);

  res.json({
    totalUsers,
    todayNewUsers: todayUsers,
    ...quotaStats
  });
});

module.exports = router;