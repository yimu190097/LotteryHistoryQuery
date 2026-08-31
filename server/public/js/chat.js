/**
 * 客服会话模块 - 管理员 Web 端
 *
 * 功能：
 * - 左侧会话列表（用户头像/最新消息/未读数）
 * - 右侧聊天窗口（消息流 + 输入框 + 发送图片/语音）
 * - WebSocket 实时收发，支持文字/图片/语音
 * - 收到 WebRTC 通话邀请时弹出接听界面
 */

// ==================== 全局状态 ====================
const ChatState = {
  ws: null,
  wsReady: false,
  currentSession: null,    // 当前选中会话的用户手机号
  sessions: new Map(),     // user_phone → session 对象
};

// ==================== 渲染入口 ====================
async function renderChat() {
  // 进入会话页时确保 ws 已连接
  ensureWsConnected();

  $('#mainContent').innerHTML = `
    <div class="chat-layout">
      <!-- 左侧会话列表 -->
      <div class="chat-sidebar">
        <div class="chat-sidebar-header">
          <h3>会话</h3>
          <span id="chatOnlineDot" class="online-dot off" title="未连接"></span>
          <button onclick="loadSessions()" class="btn-refresh">↻</button>
        </div>
        <input type="text" id="chatSearchInput" class="chat-search" placeholder="搜索手机号/昵称" oninput="onSessionSearch()">
        <div class="chat-session-list" id="chatSessionList">
          <div class="empty-state">暂无会话</div>
        </div>
      </div>

      <!-- 右侧聊天窗口 -->
      <div class="chat-main" id="chatMain">
        <div class="chat-empty">
          <div class="chat-empty-icon">💬</div>
          <p>选择左侧会话开始对话</p>
          <p class="chat-empty-hint">用户付款截图会出现在这里</p>
        </div>
      </div>
    </div>
  `;

  await loadSessions();
}

// ==================== WebSocket 连接 ====================
function ensureWsConnected() {
  if (ChatState.ws && ChatState.ws.readyState === WebSocket.OPEN) return;

  const wsUrl = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws';
  console.log('[chat] connecting ws:', wsUrl);

  const ws = new WebSocket(wsUrl);
  ChatState.ws = ws;

  ws.onopen = () => {
    // 鉴权
    ws.send(JSON.stringify({ type: 'auth', token: API.token }));
  };

  ws.onmessage = (evt) => {
    // P1-4: 非 JSON 数据（如代理/网关入站探测）会直接抛错并中断 handler，这里防御忽略
    let msg;
    try { msg = JSON.parse(evt.data); } catch (e) { console.warn('[chat] ignore non-JSON ws frame:', evt.data); return; }
    handleWsMessage(msg);
  };

  ws.onclose = () => {
    ChatState.wsReady = false;
    updateOnlineDot(false);
    console.log('[chat] ws closed, will reconnect in 3s');
    setTimeout(ensureWsConnected, 3000);
  };

  ws.onerror = (err) => {
    console.error('[chat] ws error', err);
  };
}

function updateOnlineDot(online) {
  const dot = document.getElementById('chatOnlineDot');
  if (!dot) return;
  dot.classList.toggle('off', !online);
  dot.classList.toggle('on', online);
  dot.title = online ? '已连接' : '未连接';
}

function handleWsMessage(msg) {
  switch (msg.type) {
    case 'auth_ok':
      ChatState.wsReady = true;
      updateOnlineDot(true);
      console.log('[chat] authed as', msg.identity);
      break;
    case 'auth_fail':
      showToast('WebSocket 认证失败，请重新登录', 'error');
      handleLogout();
      break;
    case 'chat':
      handleIncomingChat(msg);
      break;
    case 'presence':
      handlePresence(msg);
      break;
    case 'call':
      handleIncomingCall(msg);
      break;
    case 'offer':
    case 'answer':
    case 'candidate':
    case 'hangup':
      handleCallSignal(msg);
      break;
    case 'error':
      showToast(msg.error || 'WebSocket 错误', 'error');
      break;
  }
}

// ==================== 会话列表 ====================
async function loadSessions() {
  try {
    const data = await api('/api/chat/sessions?size=100');
    ChatState.sessions.clear();
    data.data.forEach(s => ChatState.sessions.set(s.user_phone, s));
    renderSessionList();
  } catch (err) {
    showToast('加载会话失败: ' + err.message, 'error');
  }
}

function onSessionSearch() {
  renderSessionList();
}

function renderSessionList() {
  const container = $('#chatSessionList');
  if (!container) return;

  const search = ($('#chatSearchInput')?.value || '').trim().toLowerCase();
  const list = Array.from(ChatState.sessions.values())
    .filter(s => !search ||
      s.user_phone.toLowerCase().includes(search) ||
      (s.user_nickname || '').toLowerCase().includes(search))
    .sort((a, b) => (b.updated_at || 0) - (a.updated_at || 0));

  if (!list.length) {
    container.innerHTML = '<div class="empty-state">暂无会话</div>';
    return;
  }

  container.innerHTML = list.map(s => {
    const isActive = s.user_phone === ChatState.currentSession;
    const unread = s.admin_unread || 0;
    const lastMsg = s.last_message_type === 'IMAGE' ? '[图片]' :
                    s.last_message_type === 'VOICE' ? '[语音]' :
                    (s.last_message || '');
    return `
      <div class="chat-session-item ${isActive ? 'active' : ''}" onclick="selectSession('${s.user_phone}')">
        <div class="chat-session-avatar">${(s.user_nickname || s.user_phone).slice(-2)}</div>
        <div class="chat-session-info">
          <div class="chat-session-top">
            <span class="chat-session-name">${s.user_nickname || s.user_phone}</span>
            ${unread > 0 ? `<span class="chat-unread-badge">${unread}</span>` : ''}
          </div>
          <div class="chat-session-last">${escapeHtml(lastMsg)}</div>
          <div class="chat-session-phone">${s.user_phone}</div>
        </div>
      </div>
    `;
  }).join('');
}

// ==================== 选择会话 ====================
async function selectSession(phone) {
  ChatState.currentSession = phone;
  renderSessionList();
  await renderChatWindow(phone);
  // 标记已读
  try {
    await api(`/api/chat/sessions/${phone}/read`, { method: 'POST' });
    const s = ChatState.sessions.get(phone);
    if (s) { s.admin_unread = 0; renderSessionList(); }
  } catch (_) {}
  // 通过 ws 也通知已读
  if (ChatState.wsReady) {
    ChatState.ws.send(JSON.stringify({ type: 'read', to: `user:${phone}` }));
  }
}

async function renderChatWindow(phone) {
  const session = ChatState.sessions.get(phone);
  const name = session?.user_nickname || phone;

  $('#chatMain').innerHTML = `
    <div class="chat-window">
      <div class="chat-window-header">
        <div>
          <div class="chat-window-name">${escapeHtml(name)}</div>
          <div class="chat-window-sub">${phone}</div>
        </div>
        <div class="chat-window-actions">
          <button onclick="quickAddQuota('${phone}', 10)" class="btn-small">+10次</button>
          <button onclick="quickAddQuota('${phone}', 50)" class="btn-small">+50次</button>
          <button onclick="setMonthly('${phone}')" class="btn-small">开通月租</button>
          <button onclick="setYearly('${phone}')" class="btn-small">开通年租</button>
        </div>
      </div>
      <div class="chat-messages" id="chatMessages">
        <div class="empty-state">加载消息中...</div>
      </div>
      <div class="chat-input-bar">
        <label class="chat-input-img-btn" title="发送图片">
          📷<input type="file" accept="image/*" onchange="sendImageFromFile(event)" style="display:none">
        </label>
        <input type="text" id="chatInputText" placeholder="输入消息..." onkeydown="if(event.key==='Enter')sendTextMessage()">
        <button onclick="sendTextMessage()">发送</button>
      </div>
    </div>
  `;

  await loadMessages(phone);
}

async function loadMessages(phone) {
  try {
    const data = await api(`/api/chat/messages/${phone}?limit=100`);
    const container = $('#chatMessages');
    if (!data.messages.length) {
      container.innerHTML = '<div class="empty-state">暂无消息，请发送首条消息</div>';
      return;
    }
    container.innerHTML = data.messages.map(m => renderMessageHtml(m)).join('');
    scrollChatToBottom();
  } catch (err) {
    $('#chatMessages').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

function renderMessageHtml(m) {
  const isUser = m.role === 'SENT';
  const cls = isUser ? 'msg-user' : 'msg-admin';
  const sender = isUser ? '用户' : '我';
  const time = formatDate(m.created_at);

  let content;
  if (m.type === 'TEXT') {
    content = `<div class="msg-bubble">${escapeHtml(m.text || '')}</div>`;
  } else if (m.type === 'IMAGE') {
    content = `<div class="msg-image"><img src="${m.media_path}" alt="付款截图" onclick="previewImage('${m.media_path}')"></div>`;
  } else if (m.type === 'VOICE') {
    content = `<div class="msg-voice" onclick="playVoice('${m.media_path}')">
      <span>🎤</span><span>${m.duration || 0}"</span>
    </div>`;
  } else {
    content = `<div class="msg-bubble">[未知类型]</div>`;
  }

  return `
    <div class="msg-row ${cls}">
      <div class="msg-meta">${sender} · ${time}</div>
      ${content}
    </div>
  `;
}

function scrollChatToBottom() {
  const c = $('#chatMessages');
  if (c) c.scrollTop = c.scrollHeight;
}

// ==================== 收到新消息 ====================
function handleIncomingChat(msg) {
  const phone = msg.sessionUserPhone;
  // 更新会话对象
  let s = ChatState.sessions.get(phone);
  if (!s) {
    s = { user_phone: phone, user_nickname: null, admin_unread: 0, user_unread: 0 };
    ChatState.sessions.set(phone, s);
  }
  s.last_message = msg.payload.type === 'TEXT' ? (msg.payload.text || '') :
                    msg.payload.type === 'IMAGE' ? '[图片]' : '[语音]';
  s.last_message_type = msg.payload.type;
  s.last_message_at = msg.createdAt;
  s.updated_at = msg.createdAt;
  if (msg.role === 'SENT') {
    s.admin_unread = (s.admin_unread || 0) + 1;
  }
  renderSessionList();

  // 如果当前正打开此会话：追加消息
  if (ChatState.currentSession === phone) {
    const container = $('#chatMessages');
    if (container) {
      // 清除"暂无消息"占位
      if (container.querySelector('.empty-state')) container.innerHTML = '';
      const m = {
        role: msg.role,
        type: msg.payload.type,
        text: msg.payload.text,
        media_path: ApiClient_fileUrl(msg.payload.mediaPath),
        duration: msg.payload.duration,
        created_at: msg.createdAt
      };
      container.insertAdjacentHTML('beforeend', renderMessageHtml(m));
      scrollChatToBottom();
      // 自动已读
      api(`/api/chat/sessions/${phone}/read`, { method: 'POST' }).catch(() => {});
      s.admin_unread = 0;
      renderSessionList();
    }
  }

  // 浏览器桌面通知（可选）
  if (msg.role === 'SENT' && document.hidden) {
    new Notification('新消息：' + phone, { body: s.last_message });
  }
}

// server 返回的 mediaPath 是 /uploads/xxx.jpg，前端拼接同源 host
function ApiClient_fileUrl(path) {
  if (!path) return null;
  if (path.startsWith('http')) return path;
  return path;  // 同源相对路径，浏览器自动加 host
}

function handlePresence(msg) {
  // 用户上线/下线（可显示在会话列表头像上，这里简化处理）
  console.log('[chat] presence:', msg.user, msg.online);
}

// ==================== 发送消息 ====================
function sendTextMessage() {
  const input = $('#chatInputText');
  const text = input.value.trim();
  if (!text || !ChatState.currentSession) return;
  input.value = '';

  const phone = ChatState.currentSession;
  ChatState.ws.send(JSON.stringify({
    type: 'chat',
    to: `user:${phone}`,
    payload: { type: 'TEXT', text }
  }));

  // 乐观追加到 UI
  const container = $('#chatMessages');
  if (container.querySelector('.empty-state')) container.innerHTML = '';
  container.insertAdjacentHTML('beforeend', renderMessageHtml({
    role: 'RECEIVED',
    type: 'TEXT',
    text,
    created_at: Date.now()
  }));
  scrollChatToBottom();
}

async function sendImageFromFile(event) {
  const file = event.target.files[0];
  if (!file || !ChatState.currentSession) return;
  event.target.value = ''; // 清空允许重复选同一张

  // 上传
  const formData = new FormData();
  formData.append('file', file);
  try {
    const res = await fetch('/api/upload', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${API.token}` },
      body: formData
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '上传失败');

    // 通过 ws 发送
    ChatState.ws.send(JSON.stringify({
      type: 'chat',
      to: `user:${ChatState.currentSession}`,
      payload: { type: 'IMAGE', mediaPath: data.url }
    }));

    // 乐观追加
    const container = $('#chatMessages');
    if (container.querySelector('.empty-state')) container.innerHTML = '';
    container.insertAdjacentHTML('beforeend', renderMessageHtml({
      role: 'RECEIVED',
      type: 'IMAGE',
      media_path: data.url,
      created_at: Date.now()
    }));
    scrollChatToBottom();
  } catch (err) {
    showToast('图片上传失败: ' + err.message, 'error');
  }
}

// ==================== 快捷操作：加次数/月租/年租 ====================
async function quickAddQuota(phone, count) {
  if (!confirm(`确定为 ${phone} 增加 ${count} 次查询吗？`)) return;
  try {
    await api(`/api/users/${phone}/quota/add`, {
      method: 'POST',
      body: JSON.stringify({ count })
    });
    showToast(`已为 ${phone} 增加 ${count} 次`, 'success');
    // 顺便给用户发条通知消息
    if (ChatState.wsReady) {
      ChatState.ws.send(JSON.stringify({
        type: 'chat',
        to: `user:${phone}`,
        payload: { type: 'TEXT', text: `管理员已为您增加 ${count} 次查询次数，请查收。` }
      }));
    }
  } catch (err) {
    showToast('操作失败: ' + err.message, 'error');
  }
}

async function setMonthly(phone) {
  const days = prompt('开通月租天数（默认30）', '30');
  if (!days) return;
  try {
    await api(`/api/users/${phone}/quota/plan`, {
      method: 'POST',
      body: JSON.stringify({ planType: 'MONTHLY', days: parseInt(days) })
    });
    showToast(`已开通 ${phone} 月租 ${days} 天`, 'success');
    if (ChatState.wsReady) {
      ChatState.ws.send(JSON.stringify({
        type: 'chat',
        to: `user:${phone}`,
        payload: { type: 'TEXT', text: `已为您开通月租服务（${days} 天），感谢支持！` }
      }));
    }
  } catch (err) {
    showToast('操作失败: ' + err.message, 'error');
  }
}

async function setYearly(phone) {
  if (!confirm(`确定为 ${phone} 开通年租（365天）吗？`)) return;
  try {
    await api(`/api/users/${phone}/quota/plan`, {
      method: 'POST',
      body: JSON.stringify({ planType: 'MONTHLY', days: 365 })
    });
    showToast(`已开通 ${phone} 年租`, 'success');
    if (ChatState.wsReady) {
      ChatState.ws.send(JSON.stringify({
        type: 'chat',
        to: `user:${phone}`,
        payload: { type: 'TEXT', text: '已为您开通年租服务（365天），感谢支持！' }
      }));
    }
  } catch (err) {
    showToast('操作失败: ' + err.message, 'error');
  }
}

// ==================== 工具函数 ====================
function escapeHtml(s) {
  if (!s) return '';
  return String(s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function previewImage(url) {
  const overlay = document.createElement('div');
  overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.85);z-index:9999;display:flex;align-items:center;justify-content:center;cursor:zoom-out';
  overlay.innerHTML = `<img src="${url}" style="max-width:90%;max-height:90%;">`;
  overlay.onclick = () => overlay.remove();
  document.body.appendChild(overlay);
}

function playVoice(url) {
  const audio = new Audio(url);
  audio.play().catch(err => showToast('播放失败: ' + err.message, 'error'));
}

// ==================== WebRTC 通话（占位，阶段3实现） ====================
function handleIncomingCall(msg) {
  console.log('[chat] incoming call from', msg.from);
  // 阶段3实现：弹出接听界面
}

function handleCallSignal(msg) {
  console.log('[chat] call signal:', msg.type, msg.from);
  // 阶段3实现
}
