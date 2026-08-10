const express = require('express');
const { db } = require('../db/database');
const { authMiddleware } = require('../middleware/auth');

const router = express.Router();

// 所有接口需要登录
router.use(authMiddleware);

/**
 * GET /api/stats/dashboard - 仪表盘数据
 */
router.get('/dashboard', (req, res) => {
  const now = Date.now();
  const todayStart = new Date().setHours(0, 0, 0, 0);
  const weekStart = now - 7 * 24 * 60 * 60 * 1000;
  const monthStart = now - 30 * 24 * 60 * 60 * 1000;

  const totalUsers = db.prepare('SELECT COUNT(*) as count FROM users').get().count;
  const todayUsers = db.prepare('SELECT COUNT(*) as count FROM users WHERE created_at >= ?').get(todayStart).count;
  const weekUsers = db.prepare('SELECT COUNT(*) as count FROM users WHERE created_at >= ?').get(weekStart).count;

  const totalQueries = db.prepare(
    "SELECT COUNT(*) as count FROM audit_log WHERE action = 'QUERY_CONSUME'"
  ).get().count;
  const todayQueries = db.prepare(
    "SELECT COUNT(*) as count FROM audit_log WHERE action = 'QUERY_CONSUME' AND created_at >= ?"
  ).get(todayStart).count;

  const quotaStats = db.prepare(`
    SELECT
      COUNT(*) as total,
      SUM(CASE WHEN plan_type = 'PAY_PER_USE' THEN 1 ELSE 0 END) as pay_per_use_count,
      SUM(CASE WHEN plan_type = 'MONTHLY' THEN 1 ELSE 0 END) as monthly_count,
      SUM(remaining_queries) as total_remaining
    FROM quotas
  `).get();

  // 最近操作日志
  const recentLogs = db.prepare(
    'SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 20'
  ).all();

  res.json({
    stats: {
      totalUsers,
      todayNewUsers: todayUsers,
      weekNewUsers: weekUsers,
      totalQueries,
      todayQueries,
      quotaStats
    },
    recentLogs
  });
});

/**
 * GET /api/stats/queries - 查询统计（按天）
 */
router.get('/queries', (req, res) => {
  const days = parseInt(req.query.days) || 30;
  const since = Date.now() - days * 24 * 60 * 60 * 1000;

  const data = db.prepare(`
    SELECT
      date(created_at / 1000, 'unixepoch') as day,
      COUNT(*) as count
    FROM audit_log
    WHERE action = 'QUERY_CONSUME' AND created_at >= ?
    GROUP BY day
    ORDER BY day ASC
  `).all(since);

  res.json(data);
});

/**
 * GET /api/stats/audit-log - 操作日志
 */
router.get('/audit-log', (req, res) => {
  const page = parseInt(req.query.page) || 1;
  const size = parseInt(req.query.size) || 50;
  const offset = (page - 1) * size;

  const total = db.prepare('SELECT COUNT(*) as total FROM audit_log').get().total;
  const logs = db.prepare(
    'SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ? OFFSET ?'
  ).all(size, offset);

  res.json({
    total,
    page,
    size,
    totalPages: Math.ceil(total / size),
    data: logs
  });
});

module.exports = router;