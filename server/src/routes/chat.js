const express = require('express');
const { db } = require('../db/database');
const { authMiddleware } = require('../middleware/auth');

const router = express.Router();

// 所有接口需要管理员登录
router.use(authMiddleware);

/**
 * GET /api/chat/sessions - 客服会话列表（按最新消息时间倒序）
 * Query: page, size, search
 */
router.get('/sessions', (req, res) => {
  const page = Math.max(1, parseInt(req.query.page) || 1);
  const size = Math.min(100, Math.max(1, parseInt(req.query.size) || 50));
  const search = req.query.search || '';
  const offset = (page - 1) * size;

  let where = '';
  const params = [];
  if (search) {
    where = 'WHERE user_phone LIKE ? OR user_nickname LIKE ?';
    params.push(`%${search}%`, `%${search}%`);
  }

  const total = db.prepare(`SELECT COUNT(*) as total FROM chat_sessions ${where}`).get(...params).total;
  const sessions = db.prepare(`
    SELECT user_phone, user_nickname, last_message, last_message_at, last_message_type,
           user_unread, admin_unread, created_at, updated_at
    FROM chat_sessions ${where}
    ORDER BY updated_at DESC
    LIMIT ? OFFSET ?
  `).all(...params, size, offset);

  res.json({ total, page, size, totalPages: Math.ceil(total / size), data: sessions });
});

/**
 * GET /api/chat/sessions/:phone - 单个会话详情（含未读数）
 */
router.get('/sessions/:phone', (req, res) => {
  const session = db.prepare('SELECT * FROM chat_sessions WHERE user_phone = ?').get(req.params.phone);
  if (!session) return res.status(404).json({ error: '会话不存在' });
  res.json(session);
});

/**
 * GET /api/chat/messages/:phone - 某用户的历史消息（分页）
 * Query: before (毫秒时间戳，拉取此时间之前的消息), limit
 */
router.get('/messages/:phone', (req, res) => {
  const phone = req.params.phone;
  const limit = Math.min(100, Math.max(1, parseInt(req.query.limit) || 50));
  const before = parseInt(req.query.before) || Date.now() + 1;

  const messages = db.prepare(`
    SELECT id, session_user_phone, role, type, text, media_path, duration, created_at
    FROM chat_messages
    WHERE session_user_phone = ? AND created_at < ?
    ORDER BY created_at DESC
    LIMIT ?
  `).all(phone, before, limit);

  res.json({ messages: messages.reverse() });
});

/**
 * POST /api/chat/sessions/:phone/read - 管理员标记会话已读
 */
router.post('/sessions/:phone/read', (req, res) => {
  db.prepare('UPDATE chat_sessions SET admin_unread = 0, updated_at = ? WHERE user_phone = ?')
    .run(Date.now(), req.params.phone);
  res.json({ message: '已标记已读' });
});

module.exports = router;
