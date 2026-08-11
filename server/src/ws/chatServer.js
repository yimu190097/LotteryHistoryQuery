const { WebSocketServer } = require('ws');
const jwt = require('jsonwebtoken');
const { db } = require('../db/database');
const { JWT_SECRET } = require('../middleware/auth');

/**
 * WebSocket 聊天服务
 *
 * 协议（JSON 帧）：
 *   客户端 → 服务端：
 *     {type:"auth", token:"<JWT>"}                              建立连接后第一帧认证
 *     {type:"chat", to:"admin", payload:{type,text,mediaPath,duration}}
 *     {type:"call",  to:"admin", payload:{}}                    发起通话
 *     {type:"offer", to:"admin", payload:{sdp}}                  WebRTC SDP
 *     {type:"answer",to:"user:138...", payload:{sdp}}            WebRTC SDP
 *     {type:"candidate", to:..., payload:{candidate}}           ICE 候选
 *     {type:"hangup", to:..., payload:{}}                       挂断
 *     {type:"read", to:"admin"}                                 标记管理员已读
 *
 *   服务端 → 客户端：
 *     {type:"auth_ok", identity:"user:138..."|"admin:1"}
 *     {type:"auth_fail", error:"..."}
 *     {type:"chat", from:"user:138...", payload:{...}, id, createdAt}
 *     {type:"call",  from:"user:138..."}
 *     {type:"offer","answer","candidate","hangup" 同理带 from}
 *     {type:"presence", user:"138...", online:true|false}       仅推给管理员
 *
 * 连接管理：identity = "user:<phone>" 或 "admin:<id>"
 */

const clients = new Map(); // identity → Set<WebSocket>

function setupWebSocket(server) {
  const wss = new WebSocketServer({ server, path: '/ws' });

  wss.on('connection', (ws, req) => {
    let identity = null;
    let role = null;
    let userId = null;

    // 30 秒内未认证则关闭
    const authTimer = setTimeout(() => {
      if (!identity) {
        try { ws.send(JSON.stringify({ type: 'auth_fail', error: '认证超时' })); } catch (_) {}
        ws.close(4001, 'auth timeout');
      }
    }, 30000);

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); }
      catch (_) { return; }

      // 1) 认证
      if (msg.type === 'auth') {
        if (identity) return; // 已认证
        try {
          const decoded = jwt.verify(msg.token, JWT_SECRET);
          if (decoded.role) {
            // 管理员
            identity = `admin:${decoded.id}`;
            role = 'admin';
            userId = decoded.id;
          } else {
            // 客户端用户（client/login 返回的 JWT 没有 role 字段）
            identity = `user:${decoded.phone}`;
            role = 'user';
          }
          clearTimeout(authTimer);
          if (!clients.has(identity)) clients.set(identity, new Set());
          clients.get(identity).add(ws);
          ws.send(JSON.stringify({ type: 'auth_ok', identity, role }));
          // 用户上线 → 推给所有在线管理员
          if (role === 'user') {
            broadcastToAdmins({
              type: 'presence', user: decoded.phone, online: true
            });
          }
        } catch (e) {
          ws.send(JSON.stringify({ type: 'auth_fail', error: 'Token 无效或已过期' }));
          ws.close(4002, 'invalid token');
        }
        return;
      }

      // 其余消息必须先认证
      if (!identity) {
        ws.send(JSON.stringify({ type: 'error', error: '请先认证' }));
        return;
      }

      // 2) 聊天消息
      if (msg.type === 'chat') {
        handleChat(ws, identity, role, userId, msg);
        return;
      }

      // 3) 已读标记
      if (msg.type === 'read') {
        handleRead(identity, role, msg.to);
        return;
      }

      // 4) WebRTC 信令：直接转发给目标
      if (['call', 'offer', 'answer', 'candidate', 'hangup'].includes(msg.type)) {
        forwardTo(ws, identity, msg.type, msg.to, msg.payload);
        return;
      }
    });

    ws.on('close', () => {
      clearTimeout(authTimer);
      if (identity) {
        const set = clients.get(identity);
        if (set) {
          set.delete(ws);
          if (set.size === 0) clients.delete(identity);
        }
        // 用户下线 → 推给管理员
        if (role === 'user') {
          const phone = identity.split(':')[1];
          broadcastToAdmins({ type: 'presence', user: phone, online: isUserOnline(phone) });
        }
      }
    });
  });

  return wss;
}

// ============ 聊天消息处理 ============
function handleChat(ws, fromIdentity, role, adminId, msg) {
  const { to, payload } = msg;
  if (!payload || !payload.type) return;

  const now = Date.now();
  let sessionPhone, messageRole;

  if (role === 'user') {
    // 用户 → 管理员：会话以用户手机号标识
    sessionPhone = fromIdentity.split(':')[1];
    messageRole = 'SENT';
  } else {
    // 管理员 → 用户：to 必须是 "user:<phone>"
    if (!to || !to.startsWith('user:')) return;
    sessionPhone = to.split(':')[1];
    messageRole = 'RECEIVED';
  }

  // 确保 session 存在
  const user = db.prepare('SELECT phone, nickname FROM users WHERE phone = ?').get(sessionPhone);
  if (!user) {
    ws.send(JSON.stringify({ type: 'error', error: '用户不存在' }));
    return;
  }

  db.prepare(`
    INSERT INTO chat_sessions (user_phone, user_nickname, last_message, last_message_at, last_message_type,
      user_unread, admin_unread, created_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(user_phone) DO UPDATE SET
      user_nickname = excluded.user_nickname,
      last_message = excluded.last_message,
      last_message_at = excluded.last_message_at,
      last_message_type = excluded.last_message_type,
      user_unread = chat_sessions.user_unread + excluded.user_unread,
      admin_unread = chat_sessions.admin_unread + excluded.admin_unread,
      updated_at = excluded.updated_at
  `).run(
    sessionPhone,
    user.nickname,
    payload.type === 'TEXT' ? (payload.text || '') :
      (payload.type === 'IMAGE' ? '[图片]' : '[语音]'),
    now,
    payload.type,
    role === 'admin' ? 1 : 0,    // 管理员发的消息，用户未读+1
    role === 'user' ? 1 : 0,     // 用户发的消息，管理员未读+1
    now,
    now
  );

  // 存储消息
  const result = db.prepare(`
    INSERT INTO chat_messages (session_user_phone, role, type, text, media_path, duration, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).run(
    sessionPhone,
    messageRole,
    payload.type,
    payload.text || null,
    payload.mediaPath || null,
    payload.duration || null,
    now
  );

  // 构建推送消息
  const outMsg = {
    type: 'chat',
    id: result.lastInsertRowid,
    from: fromIdentity,
    sessionUserPhone: sessionPhone,
    role: messageRole,
    payload: { type: payload.type, text: payload.text || null, mediaPath: payload.mediaPath || null, duration: payload.duration || null },
    createdAt: now
  };

  if (role === 'user') {
    // 推给所有在线管理员
    broadcastToAdmins(outMsg);
  } else {
    // 推给指定用户
    sendTo(`user:${sessionPhone}`, outMsg);
  }
}

// ============ 已读标记 ============
function handleRead(fromIdentity, role, to) {
  let sessionPhone;
  if (role === 'user') {
    sessionPhone = fromIdentity.split(':')[1];
    db.prepare('UPDATE chat_sessions SET user_unread = 0, updated_at = ? WHERE user_phone = ?')
      .run(Date.now(), sessionPhone);
  } else if (to && to.startsWith('user:')) {
    sessionPhone = to.split(':')[1];
    db.prepare('UPDATE chat_sessions SET admin_unread = 0, updated_at = ? WHERE user_phone = ?')
      .run(Date.now(), sessionPhone);
  }
}

// ============ 转发工具 ============
function sendTo(identity, msg) {
  const set = clients.get(identity);
  if (!set) return false;
  const data = JSON.stringify(msg);
  let sent = false;
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) {
      try { ws.send(data); sent = true; } catch (_) {}
    }
  }
  return sent;
}

function broadcastToAdmins(msg) {
  for (const [identity] of clients) {
    if (identity.startsWith('admin:')) {
      sendTo(identity, msg);
    }
  }
}

function forwardTo(fromWs, fromIdentity, type, to, payload) {
  if (!to) return;
  const msg = { type, from: fromIdentity, to, payload };
  sendTo(to, msg);
}

function isUserOnline(phone) {
  return clients.has(`user:${phone}`);
}

module.exports = { setupWebSocket, isUserOnline };
