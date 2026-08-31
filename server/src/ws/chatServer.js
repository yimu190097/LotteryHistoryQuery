const { WebSocketServer } = require('ws');
const jwt = require('jsonwebtoken');
const { db } = require('../db/database');
const { JWT_SECRET } = require('../middleware/auth');
const callMgr = require('./callManager');

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
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: 1024 * 1024 });

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

      // 4) WebRTC 信令：经通话状态机处理后转发
      if (['call', 'accept', 'reject', 'offer', 'answer', 'candidate', 'hangup'].includes(msg.type)) {
        handleCallSignal(ws, identity, role, msg);
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
const CHAT_RATE_LIMIT = 20;        // 每窗口最多消息数
const CHAT_RATE_WINDOW = 10 * 1000; // 窗口时长(ms)
const MAX_CHAT_TEXT = 2000;         // 单条文本最大长度
const chatRate = new Map(); // identity -> { count, start }

// 简单速率限制：每连接每 10 秒最多 20 条聊天消息，防刷屏打爆 DB/轰炸管理员
function checkChatRate(ws, identity) {
  const now = Date.now();
  let r = chatRate.get(identity);
  if (!r || now - r.start > CHAT_RATE_WINDOW) {
    chatRate.set(identity, { count: 1, start: now });
    return true;
  }
  r.count++;
  if (r.count > CHAT_RATE_LIMIT) {
    try { ws.send(JSON.stringify({ type: 'error', error: '消息发送过于频繁，请稍后再试' })); } catch (_) {}
    return false;
  }
  return true;
}

function handleChat(ws, fromIdentity, role, adminId, msg) {
  const { to, payload } = msg;
  if (!payload || !payload.type) return;

  // 频率限制 + 文本长度上限
  if (!checkChatRate(ws, fromIdentity)) return;
  const text = String(payload.text || '').slice(0, MAX_CHAT_TEXT);

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
    payload.type === 'TEXT' ? text :
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
    text || null,
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
    payload: { type: payload.type, text: payload.type === 'TEXT' ? (text || null) : null, mediaPath: payload.mediaPath || null, duration: payload.duration || null },
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

// ============ WebRTC 通话信令处理 ============
function handleCallSignal(ws, fromIdentity, role, msg) {
  const { type, to, payload } = msg;

  // 用户发起通话
  if (type === 'call') {
    if (role !== 'user') return ws.send(JSON.stringify({ type: 'error', error: '只有用户能发起通话' }));
    // to 可以是 "admin"（推给所有在线管理员，让第一个接听的接）
    // 也允许指定 "admin:<id>"
    const call = callMgr.startCall(fromIdentity, to || 'admin:*', ws);
    if (call.error) return ws.send(JSON.stringify({ type: 'error', error: call.error }));

    const ringMsg = {
      type: 'call',
      callId: call.callId,
      from: fromIdentity,
      payload: { callId: call.callId }
    };
    if (to && to.startsWith('admin:')) {
      sendTo(to, ringMsg);
    } else {
      broadcastToAdmins(ringMsg);
    }
    // 给发起方确认
    ws.send(JSON.stringify({
      type: 'call_state', state: 'ringing', callId: call.callId
    }));
    return;
  }

  // 管理员接听
  if (type === 'accept') {
    if (role !== 'admin') return ws.send(JSON.stringify({ type: 'error', error: '只有管理员能接听' }));
    const callId = payload?.callId;
    const c = callMgr.acceptCall(callId);
    if (c?.error) return ws.send(JSON.stringify({ type: 'error', error: c.error }));
    // 通知用户开始 SDP 交换
    sendTo(c.from, {
      type: 'accept', callId: c.callId, from: fromIdentity
    });
    // 通知其他管理员取消响铃
    broadcastToAdmins({
      type: 'call_canceled', callId: c.callId, reason: '已由其他管理员接听'
    });
    return;
  }

  // 拒绝
  if (type === 'reject') {
    const callId = payload?.callId;
    const c = callMgr.getCall(callId);
    if (!c) return ws.send(JSON.stringify({ type: 'error', error: '通话不存在或已结束' }));
    // 权限校验（防越权）：仅通话发起方本人，或目标管理员（含 admin:* 广播目标）可拒绝
    const isCaller = c.from === fromIdentity;
    const isTargetAdmin = c.to === 'admin:*' ? role === 'admin' : c.to === fromIdentity;
    if (!isCaller && !isTargetAdmin) {
      return ws.send(JSON.stringify({ type: 'error', error: '无权拒绝该通话' }));
    }
    const ended = callMgr.endCall(callId, 'rejected');
    if (ended) {
      sendTo(c.from, { type: 'reject', callId: c.callId, from: fromIdentity });
    }
    return;
  }

  // offer / answer / candidate 直接转发
  if (['offer', 'answer', 'candidate'].includes(type)) {
    const callId = payload?.callId || msg.callId;
    if (callId) {
      const c = callMgr.getCall(callId);
      if (!c) return ws.send(JSON.stringify({ type: 'error', error: '通话已结束' }));
      if (type === 'answer') callMgr.markActive(callId);
    }
    forwardTo(ws, fromIdentity, type, to, payload);
    return;
  }

  // 挂断
  if (type === 'hangup') {
    const callId = payload?.callId || msg.callId;
    if (callId) {
      const c = callMgr.endCall(callId, 'hangup');
      if (c) {
        const other = c.from === fromIdentity ? c.to : c.from;
        if (other === 'admin:*') {
          broadcastToAdmins({ type: 'hangup', callId: c.callId, from: fromIdentity });
        } else {
          sendTo(other, { type: 'hangup', callId: c.callId, from: fromIdentity });
        }
      }
    } else {
      // 没带 callId，按身份查
      forwardTo(ws, fromIdentity, 'hangup', to, payload);
    }
    return;
  }
}

module.exports = { setupWebSocket, isUserOnline };
