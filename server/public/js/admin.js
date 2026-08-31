/**
 * 彩票后台管理系统 - 前端应用
 * 纯原生JS，无框架依赖，兼容所有现代浏览器
 */

// ==================== 暗黑模式 ====================
(function initTheme() {
  const saved = localStorage.getItem('theme');
  if (saved === 'dark') {
    document.documentElement.setAttribute('data-theme', 'dark');
  }
  updateThemeIcon();
})();

function toggleTheme() {
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  if (isDark) {
    document.documentElement.removeAttribute('data-theme');
    localStorage.setItem('theme', 'light');
  } else {
    document.documentElement.setAttribute('data-theme', 'dark');
    localStorage.setItem('theme', 'dark');
  }
  updateThemeIcon();
}

function updateThemeIcon() {
  const icon = document.getElementById('themeIcon');
  if (!icon) return;
  const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
  icon.textContent = isDark ? '☀️' : '🌙';
}

// ==================== 全局状态 ====================
const API = {
  base: '',
  token: localStorage.getItem('admin_token') || '',
};

// ==================== 工具函数 ====================
function $(sel) { return document.querySelector(sel); }
function $$(sel) { return document.querySelectorAll(sel); }

async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...options.headers };
  if (API.token) headers['Authorization'] = `Bearer ${API.token}`;
  const res = await fetch(API.base + path, { ...options, headers });
  if (res.status === 401) {
    localStorage.removeItem('admin_token');
    showLogin();
    throw new Error('登录已过期');
  }
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || '请求失败');
  return data;
}

function showToast(message, type = 'info') {
  const container = $('#toastContainer');
  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  toast.textContent = message;
  container.appendChild(toast);
  setTimeout(() => { toast.remove(); }, 3000);
}

function formatDate(ts) {
  if (!ts) return '—';
  const d = new Date(ts);
  return d.toLocaleString('zh-CN', { year:'numeric', month:'2-digit', day:'2-digit',
    hour:'2-digit', minute:'2-digit' });
}

function formatDateShort(ts) {
  if (!ts) return '—';
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
}

// ==================== 登录模块 ====================
async function handleLogin(e) {
  e.preventDefault();
  const username = $('#loginUsername').value.trim();
  const password = $('#loginPassword').value;
  const errEl = $('#loginError');

  try {
    const data = await api('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    });
    API.token = data.token;
    localStorage.setItem('admin_token', data.token);
    localStorage.setItem('admin_user', JSON.stringify(data.admin));
    showApp(data.admin);
    showToast('登录成功', 'success');

    // P0-3 安全加固：默认密码未修改 → 强制弹窗改密码，关闭前不能使用系统
    if (data.mustChangePassword || data.admin?.mustChangePassword) {
      setTimeout(() => {
        showToast('出于安全原因，请立即修改初始密码', 'info');
        showForceChangePasswordModal();
      }, 300);
    }
  } catch (err) {
    errEl.textContent = err.message;
    errEl.style.display = 'block';
  }
}

function handleLogout() {
  localStorage.removeItem('admin_token');
  localStorage.removeItem('admin_user');
  API.token = '';
  showLogin();
}

function showLogin() {
  $('#loginPage').style.display = 'flex';
  $('#appPage').style.display = 'none';
  $('#loginUsername').value = '';
  $('#loginPassword').value = '';
  $('#loginError').style.display = 'none';
}

function showApp(admin) {
  $('#loginPage').style.display = 'none';
  $('#appPage').style.display = 'flex';
  $('#currentUser').textContent = admin.username;
  navigate('dashboard');
}

// ==================== 导航 ====================
function navigate(page) {
  $$('.sidebar nav a').forEach(a => a.classList.remove('active'));
  $(`.sidebar nav a[data-page="${page}"]`)?.classList.add('active');

  switch (page) {
    case 'dashboard': renderDashboard(); break;
    case 'users': renderUsers(); break;
    case 'chat': renderChat && renderChat(); break;
    case 'audit': renderAuditLog(); break;
    case 'settings': renderSettings(); break;
    case 'downloads': renderDownloads(); break;
  }
}

// ==================== 仪表盘 ====================
async function renderDashboard() {
  $('#mainContent').innerHTML = `
    <div class="page-header"><h1>仪表盘</h1></div>
    <div class="stats-grid" id="statsGrid"><div class="empty-state">加载中...</div></div>
    <div class="card">
      <div class="card-header"><h3>最近操作日志</h3></div>
      <div class="table-container" id="recentLogs"><div class="empty-state">加载中...</div></div>
    </div>
  `;

  try {
    const data = await api('/api/stats/dashboard');
    const s = data.stats;
    const qs = s.quotaStats || {};

    $('#statsGrid').innerHTML = `
      <div class="stat-card" style="border-left:4px solid var(--primary)">
        <div class="stat-value">${s.totalUsers}</div>
        <div class="stat-label">总用户数</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--info)">
        <div class="stat-value">${s.todayNewUsers}</div>
        <div class="stat-label">今日新增</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--warning)">
        <div class="stat-value">${s.totalQueries}</div>
        <div class="stat-label">总查询次数</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--info)">
        <div class="stat-value">${s.todayQueries}</div>
        <div class="stat-label">今日查询</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--success)">
        <div class="stat-value">${qs.monthly || 0}</div>
        <div class="stat-label">月租用户</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--info)">
        <div class="stat-value">${qs.pay_per_use || 0}</div>
        <div class="stat-label">按次用户</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--danger)">
        <div class="stat-value">${qs.expired_monthly || 0}</div>
        <div class="stat-label">已过期月租</div>
      </div>
      <div class="stat-card" style="border-left:4px solid var(--success)">
        <div class="stat-value">${qs.total_remaining || 0}</div>
        <div class="stat-label">剩余总次数</div>
      </div>
    `;

    renderLogTable($('#recentLogs'), data.recentLogs || []);
  } catch (err) {
    $('#statsGrid').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

function renderLogTable(container, logs) {
  if (!logs.length) {
    container.innerHTML = '<div class="empty-state">暂无日志</div>';
    return;
  }
  container.innerHTML = `
    <table>
      <thead><tr>
        <th>时间</th><th>操作人</th><th>操作</th><th>目标</th><th>详情</th>
      </tr></thead>
      <tbody>${logs.map(l => `
        <tr>
          <td>${formatDate(l.created_at)}</td>
          <td>${l.admin_username || '—'}</td>
          <td><span class="badge badge-info">${l.action}</span></td>
          <td>${l.target || '—'}</td>
          <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${l.detail || '—'}</td>
        </tr>
      `).join('')}</tbody>
    </table>
  `;
}

// ==================== 用户管理 ====================
let userPage = 1, userSearch = '', userPlanFilter = '';

async function renderUsers() {
  $('#mainContent').innerHTML = `
    <div class="page-header" style="display:flex;justify-content:space-between;align-items:center">
      <h1>用户管理</h1>
      <button class="btn btn-success" onclick="showRegisterUserModal()">+ 注册用户</button>
    </div>
    <div class="card">
      <div class="toolbar">
        <div class="search-bar">
          <input type="text" id="userSearch" placeholder="搜索手机号或昵称..." value="${userSearch}"
            onkeydown="if(event.key==='Enter'){userSearch=this.value;userPage=1;renderUsers()}">
          <button class="btn btn-primary" onclick="userSearch=$('#userSearch').value;userPage=1;renderUsers()">搜索</button>
        </div>
        <select id="userPlanFilter" onchange="userPlanFilter=this.value;userPage=1;renderUsers()" style="padding:8px 12px;border:1px solid var(--border);border-radius:var(--radius);font-size:14px;min-width:120px">
          <option value="" ${userPlanFilter === '' ? 'selected' : ''}>全部套餐</option>
          <option value="PAY_PER_USE" ${userPlanFilter === 'PAY_PER_USE' ? 'selected' : ''}>按次用户</option>
          <option value="MONTHLY" ${userPlanFilter === 'MONTHLY' ? 'selected' : ''}>月租用户</option>
        </select>
      </div>
      <div class="table-container" id="userTable"><div class="empty-state">加载中...</div></div>
      <div id="userPagination"></div>
    </div>
  `;

  try {
    let url = `/api/users?page=${userPage}&size=20`;
    if (userSearch) url += `&search=${encodeURIComponent(userSearch)}`;
    if (userPlanFilter) url += `&planType=${encodeURIComponent(userPlanFilter)}`;
    const data = await api(url);
    if (!data.data.length) {
      $('#userTable').innerHTML = '<div class="empty-state">暂无用户数据</div>';
      $('#userPagination').innerHTML = '';
      return;
    }

    const now = Date.now();
    $('#userTable').innerHTML = `
      <table>
        <thead><tr>
          <th>手机号</th><th>昵称</th><th>套餐类型</th><th>状态</th><th>剩余次数</th>
          <th>到期时间</th><th>终端</th><th>注册时间</th><th>操作</th>
        </tr></thead>
        <tbody>${data.data.map(u => {
          const isMonthly = u.plan_type === 'MONTHLY';
          const isExpired = isMonthly && u.monthly_expire_at && u.monthly_expire_at < now;
          const statusText = isMonthly ? (isExpired ? '已过期' : '正常') : '—';
          const statusBadge = isMonthly ? (isExpired ? 'badge-danger' : 'badge-success') : 'badge-info';
          const quotaColor = (u.remaining_queries ?? 0) > 0 ? '#2E7D32' : '#C62828';
          const sc = u.session_count || 0;
          const sessionColor = sc >= 3 ? '#C62828' : sc > 0 ? '#2E7D32' : '#9E9E9E';
          return `
          <tr>
            <td><strong>${u.phone}</strong></td>
            <td>${u.nickname || '—'}</td>
            <td><span class="badge ${isMonthly ? 'badge-success' : 'badge-info'}">${isMonthly ? '月租' : '按次'}</span></td>
            <td><span class="badge ${statusBadge}">${statusText}</span></td>
            <td style="font-weight:600;color:${quotaColor}">${u.remaining_queries ?? 0}</td>
            <td>${u.monthly_expire_at ? formatDateShort(u.monthly_expire_at) : '—'}</td>
            <td><span style="font-weight:600;color:${sessionColor};cursor:pointer" onclick="showUserSessions('${u.phone}')" title="点击管理终端">${sc} 台</span></td>
            <td>${formatDateShort(u.created_at)}</td>
            <td style="white-space:nowrap">
              <button class="btn btn-outline btn-sm" onclick="showUserDetail('${u.phone}')">详情</button>
              <button class="btn btn-primary btn-sm" onclick="showQuotaModal('${u.phone}')">配额</button>
              <button class="btn btn-warning btn-sm" onclick="showResetPasswordModal('${u.phone}')">重置密码</button>
              <button class="btn btn-danger btn-sm" onclick="confirmDeleteUser('${u.phone}')">删除</button>
            </td>
          </tr>
        `}).join('')}</tbody>
      </table>
    `;

    renderPagination($('#userPagination'), data, (p) => { userPage = p; renderUsers(); });
  } catch (err) {
    $('#userTable').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

function renderPagination(container, data, onPage) {
  if (data.totalPages <= 1) { container.innerHTML = ''; return; }
  let html = '<div class="pagination">';
  html += `<button ${data.page <= 1 ? 'disabled' : ''} onclick="void(0)">«</button>`;
  for (let i = 1; i <= data.totalPages; i++) {
    if (i === 1 || i === data.totalPages || Math.abs(i - data.page) <= 2) {
      html += `<button class="${i === data.page ? 'active' : ''}" onclick="void(0)">${i}</button>`;
    } else if (i === 2 || i === data.totalPages - 1) {
      html += '<button disabled>...</button>';
    }
  }
  html += `<button ${data.page >= data.totalPages ? 'disabled' : ''} onclick="void(0)">»</button>`;
  html += '</div>';
  container.innerHTML = html;

  const buttons = container.querySelectorAll('button:not([disabled])');
  let btnIdx = 0;
  if (data.page > 1) { buttons[btnIdx].onclick = () => onPage(data.page - 1); btnIdx++; }
  for (let i = 1; i <= data.totalPages; i++) {
    if (i === 1 || i === data.totalPages || Math.abs(i - data.page) <= 2) {
      buttons[btnIdx].onclick = () => onPage(i);
      btnIdx++;
    }
  }
  if (data.page < data.totalPages) {
    buttons[btnIdx].onclick = () => onPage(data.page + 1);
  }
}

async function showUserDetail(phone) {
  try {
    const data = await api(`/api/users/${phone}`);
    const u = data.user;
    const isMonthly = u.plan_type === 'MONTHLY';
    const isExpired = isMonthly && u.monthly_expire_at && u.monthly_expire_at < Date.now();
    $('#modalContent').innerHTML = `
      <h3>用户详情 - ${u.phone}</h3>
      <div class="detail-grid">
        <div class="detail-item"><label>手机号</label><span>${u.phone}</span></div>
        <div class="detail-item"><label>昵称</label><span>${u.nickname || '—'}</span></div>
        <div class="detail-item"><label>套餐类型</label><span class="badge ${isMonthly ? 'badge-success' : 'badge-info'}">${isMonthly ? '月租用户' : '按次用户'}</span></div>
        <div class="detail-item"><label>状态</label><span class="badge ${isMonthly ? (isExpired ? 'badge-danger' : 'badge-success') : 'badge-info'}">${isMonthly ? (isExpired ? '已过期' : '正常') : '—'}</span></div>
        <div class="detail-item"><label>剩余查询次数</label><span style="font-weight:600;color:${(u.remaining_queries ?? 0) > 0 ? '#2E7D32' : '#C62828'}">${u.remaining_queries ?? 0}</span></div>
        <div class="detail-item"><label>月租到期</label><span>${u.monthly_expire_at ? formatDate(u.monthly_expire_at) : '—'}</span></div>
        <div class="detail-item"><label>注册时间</label><span>${formatDate(u.created_at)}</span></div>
        <div class="detail-item"><label>最后更新</label><span>${formatDate(u.updated_at)}</span></div>
      </div>
      <div class="modal-actions">
        <button class="btn btn-outline" onclick="closeModal()">关闭</button>
        <button class="btn btn-primary" onclick="closeModal();showQuotaModal('${u.phone}')">修改配额</button>
        <button class="btn btn-warning" onclick="closeModal();showResetPasswordModal('${u.phone}')">重置密码</button>
      </div>
    `;
    openModal();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

async function showQuotaModal(phone) {
  // 先获取用户当前配额信息
  let currentQuota = null;
  try {
    const data = await api(`/api/users/${phone}`);
    currentQuota = data.user;
  } catch (e) { /* 忽略 */ }

  const planType = currentQuota?.plan_type || 'PAY_PER_USE';
  const remaining = currentQuota?.remaining_queries ?? 10;
  const expireAt = currentQuota?.monthly_expire_at || '';

  $('#modalContent').innerHTML = `
    <h3>设置配额 - ${phone}</h3>
    <form onsubmit="handleSetQuota(event, '${phone}')">
      <div class="form-group">
        <label>套餐类型</label>
        <select id="quotaPlanType" onchange="onQuotaPlanChange()">
          <option value="PAY_PER_USE" ${planType === 'PAY_PER_USE' ? 'selected' : ''}>按次付费</option>
          <option value="MONTHLY" ${planType === 'MONTHLY' ? 'selected' : ''}>月租用户</option>
        </select>
      </div>
      <div class="form-group" id="quotaRemainingGroup" style="display:${planType === 'MONTHLY' ? 'none' : 'block'}">
        <label>剩余查询次数</label>
        <input type="number" id="quotaRemaining" value="${remaining}" min="0" required>
      </div>
      <div class="form-group" id="quotaExpireGroup" style="display:${planType === 'MONTHLY' ? 'block' : 'none'}">
        <label>月租到期时间</label>
        <input type="date" id="quotaExpireDate" value="${expireAt ? new Date(expireAt).toISOString().split('T')[0] : new Date(Date.now() + 365*24*60*60*1000).toISOString().split('T')[0]}">
      </div>
      <div class="modal-actions">
        <button type="button" class="btn btn-outline" onclick="closeModal()">取消</button>
        <button type="submit" class="btn btn-primary">保存</button>
      </div>
    </form>
  `;
  openModal();
}

function onQuotaPlanChange() {
  const planType = $('#quotaPlanType').value;
  $('#quotaRemainingGroup').style.display = planType === 'MONTHLY' ? 'none' : 'block';
  $('#quotaExpireGroup').style.display = planType === 'MONTHLY' ? 'block' : 'none';
}

async function handleSetQuota(e, phone) {
  e.preventDefault();
  const planType = $('#quotaPlanType').value;
  const remainingQueries = planType === 'MONTHLY' ? 99999 : parseInt($('#quotaRemaining').value) || 10;
  let monthlyExpireAt = null;
  if (planType === 'MONTHLY') {
    monthlyExpireAt = new Date($('#quotaExpireDate').value).getTime();
  }

  try {
    await api(`/api/users/${phone}/quota`, {
      method: 'PUT',
      body: JSON.stringify({ planType, remainingQueries, monthlyExpireAt })
    });
    showToast('配额设置成功', 'success');
    closeModal();
    renderUsers();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ==================== 终端管理 ====================
async function showUserSessions(phone) {
  try {
    const data = await api(`/api/users/${phone}/sessions`);
    const sessions = data.sessions || [];
    $('#modalContent').innerHTML = `
      <h3>终端管理 - ${phone} <span style="font-size:13px;color:var(--text-secondary);font-weight:normal">（最多 ${data.maxSessions} 台）</span></h3>
      ${sessions.length === 0 ? '<div class="empty-state">暂无活跃终端</div>' : `
        <div style="max-height:320px;overflow-y:auto">
          <table>
            <thead><tr><th>设备</th><th>IP</th><th>状态</th><th>登录时间</th><th>最近活跃</th><th>操作</th></tr></thead>
            <tbody>${sessions.map(s => {
              const dev = (s.deviceInfo || 'Unknown').substring(0, 60);
              const onlineBadge = s.online ? '<span class="badge badge-success">在线</span>' : '<span class="badge" style="background:#EEE;color:#999">离线</span>';
              return `<tr>
                <td style="font-size:12px;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${s.deviceInfo || ''}">${dev}</td>
                <td style="font-size:12px">${s.ipAddress || '—'}</td>
                <td>${onlineBadge}</td>
                <td>${formatDate(s.createdAt)}</td>
                <td>${formatDate(s.lastActiveAt)}</td>
                <td><button class="btn btn-danger btn-sm" onclick="deleteUserSession('${phone}',${s.id})">踢下线</button></td>
              </tr>`;
            }).join('')}</tbody>
          </table>
        </div>
      `}
      <div class="modal-actions">
        <button class="btn btn-outline" onclick="closeModal()">关闭</button>
      </div>
    `;
    openModal();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

async function deleteUserSession(phone, sessionId) {
  if (!confirm('确定要踢掉这个终端吗？')) return;
  try {
    await api(`/api/users/${phone}/sessions/${sessionId}`, { method: 'DELETE' });
    showToast('终端已删除', 'success');
    showUserSessions(phone);
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ==================== 注册用户 ====================
function showRegisterUserModal() {
  $('#modalContent').innerHTML = `
    <h3>注册新用户</h3>
    <form onsubmit="handleRegisterUser(event)">
      <div class="form-group">
        <label>手机号 <span style="color:red">*</span></label>
        <input type="text" id="regPhone" placeholder="请输入11位手机号" required maxlength="11" pattern="1[3-9]\\d{9}" title="请输入正确的手机号">
      </div>
      <div class="form-group">
        <label>密码 <span style="color:red">*</span></label>
        <input type="password" id="regPassword" placeholder="至少6位" required minlength="6">
      </div>
      <div class="form-group">
        <label>昵称</label>
        <input type="text" id="regNickname" placeholder="选填">
      </div>
      <div class="form-group">
        <label>套餐类型</label>
        <select id="regPlanType" onchange="onRegPlanChange()">
          <option value="PAY_PER_USE">按次付费</option>
          <option value="MONTHLY">月租用户</option>
        </select>
      </div>
      <div class="form-group" id="regQuotaGroup">
        <label>初始查询次数</label>
        <input type="number" id="regQuota" value="10" min="0" required>
      </div>
      <div class="form-group" id="regExpireGroup" style="display:none">
        <label>月租到期时间</label>
        <input type="date" id="regExpireDate" value="${new Date(Date.now() + 365*24*60*60*1000).toISOString().split('T')[0]}">
      </div>
      <div class="modal-actions">
        <button type="button" class="btn btn-outline" onclick="closeModal()">取消</button>
        <button type="submit" class="btn btn-primary">注册</button>
      </div>
    </form>
  `;
  openModal();
}

function onRegPlanChange() {
  const planType = $('#regPlanType').value;
  $('#regQuotaGroup').style.display = planType === 'MONTHLY' ? 'none' : 'block';
  $('#regExpireGroup').style.display = planType === 'MONTHLY' ? 'block' : 'none';
}

async function handleRegisterUser(e) {
  e.preventDefault();
  const phone = $('#regPhone').value.trim();
  const password = $('#regPassword').value;
  const nickname = $('#regNickname').value.trim();
  const planType = $('#regPlanType').value;
  const remainingQueries = planType === 'MONTHLY' ? 99999 : parseInt($('#regQuota').value) || 10;
  let monthlyExpireAt = null;
  if (planType === 'MONTHLY') {
    monthlyExpireAt = new Date($('#regExpireDate').value).getTime();
  }

  try {
    await api('/api/users/register', {
      method: 'POST',
      body: JSON.stringify({ phone, password, nickname: nickname || undefined, planType, remainingQueries, monthlyExpireAt })
    });
    showToast(`用户 ${phone} 注册成功（${planType === 'MONTHLY' ? '月租' : '按次'}）`, 'success');
    closeModal();
    renderUsers();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ==================== 重置密码 ====================
function showResetPasswordModal(phone) {
  $('#modalContent').innerHTML = `
    <h3>重置密码 - ${phone}</h3>
    <form onsubmit="handleResetPassword(event, '${phone}')">
      <div class="form-group">
        <label>新密码 <span style="color:red">*</span></label>
        <input type="password" id="resetPassword" placeholder="至少6位" required minlength="6">
      </div>
      <div class="form-group">
        <label>确认密码 <span style="color:red">*</span></label>
        <input type="password" id="resetPassword2" placeholder="再次输入新密码" required minlength="6">
      </div>
      <div class="modal-actions">
        <button type="button" class="btn btn-outline" onclick="closeModal()">取消</button>
        <button type="submit" class="btn btn-warning">重置密码</button>
      </div>
    </form>
  `;
  openModal();
}

async function handleResetPassword(e, phone) {
  e.preventDefault();
  const p1 = $('#resetPassword').value;
  const p2 = $('#resetPassword2').value;
  if (p1 !== p2) {
    showToast('两次输入的密码不一致', 'error');
    return;
  }
  try {
    await api(`/api/users/${phone}/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ newPassword: p1 })
    });
    showToast('密码重置成功', 'success');
    closeModal();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ==================== 删除用户 ====================
function confirmDeleteUser(phone) {
  $('#modalContent').innerHTML = `
    <h3>确认删除</h3>
    <p style="color:var(--danger);margin:16px 0">确定要删除用户 <strong>${phone}</strong> 吗？此操作不可恢复，将同时删除该用户的配额和同步数据。</p>
    <div class="modal-actions">
      <button type="button" class="btn btn-outline" onclick="closeModal()">取消</button>
      <button class="btn btn-danger" onclick="handleDeleteUser('${phone}')">确认删除</button>
    </div>
  `;
  openModal();
}

async function handleDeleteUser(phone) {
  try {
    await api(`/api/users/${phone}`, { method: 'DELETE' });
    showToast('用户已删除', 'success');
    closeModal();
    renderUsers();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ==================== 操作日志 ====================
let auditPage = 1;
async function renderAuditLog() {
  $('#mainContent').innerHTML = `
    <div class="page-header"><h1>操作日志</h1></div>
    <div class="card">
      <div class="table-container" id="auditTable"><div class="empty-state">加载中...</div></div>
      <div id="auditPagination"></div>
    </div>
  `;

  try {
    const data = await api(`/api/stats/audit-log?page=${auditPage}&size=50`);
    if (!data.data.length) {
      $('#auditTable').innerHTML = '<div class="empty-state">暂无日志</div>';
      return;
    }
    renderLogTable($('#auditTable'), data.data);
    renderPagination($('#auditPagination'), data, (p) => { auditPage = p; renderAuditLog(); });
  } catch (err) {
    $('#auditTable').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

// ==================== 系统设置 ====================
async function renderSettings() {
  $('#mainContent').innerHTML = `
    <div class="page-header"><h1>系统设置</h1></div>
    <div class="card">
      <div class="card-header"><h3>系统配置</h3></div>
      <div id="configForm"><div class="empty-state">加载中...</div></div>
    </div>
    <div class="card">
      <div class="card-header">
        <h3>管理员列表</h3>
        <button class="btn btn-primary btn-sm" onclick="showAddAdminModal()">添加管理员</button>
      </div>
      <div class="table-container" id="adminTable"><div class="empty-state">加载中...</div></div>
    </div>
  `;

  try {
    const configs = await api('/api/config');
    const fields = [
      { key: 'app_version', label: 'APP版本号' },
      { key: 'free_quota', label: '新用户免费次数' },
      { key: 'query_price', label: '单次查询价格(元)' },
      { key: 'monthly_price', label: '月租价格(元)' },
      { key: 'annual_price', label: '年费价格(元)' },
    ];
    $('#configForm').innerHTML = fields.map(f => `
      <div class="form-group" style="display:flex;align-items:center;gap:12px;margin-bottom:10px">
        <label style="min-width:140px;margin:0">${f.label}</label>
        <input type="text" id="cfg_${f.key}" value="${configs[f.key] || ''}" style="flex:1">
        <button class="btn btn-primary btn-sm" onclick="saveConfig('${f.key}')">保存</button>
      </div>
    `).join('');
  } catch (err) {
    $('#configForm').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }

  try {
    const admins = await api('/api/config/admins');
    $('#adminTable').innerHTML = `
      <table>
        <thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>创建时间</th><th>最后登录</th></tr></thead>
        <tbody>${admins.map(a => `
          <tr>
            <td>${a.id}</td><td>${a.username}</td>
            <td><span class="badge badge-${a.role === 'super_admin' ? 'danger' : 'info'}">${a.role}</span></td>
            <td>${formatDate(a.created_at)}</td><td>${formatDate(a.last_login)}</td>
          </tr>
        `).join('')}</tbody>
      </table>
    `;
  } catch (err) {
    $('#adminTable').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

async function saveConfig(key) {
  const value = $(`#cfg_${key}`).value;
  try {
    await api(`/api/config/${key}`, { method: 'PUT', body: JSON.stringify({ value }) });
    showToast('配置已保存', 'success');
  } catch (err) {
    showToast(err.message, 'error');
  }
}

function showAddAdminModal() {
  $('#modalContent').innerHTML = `
    <h3>添加管理员</h3>
    <form onsubmit="handleAddAdmin(event)">
      <div class="form-group"><label>用户名</label><input type="text" id="newAdminUser" required></div>
      <div class="form-group"><label>密码</label><input type="password" id="newAdminPass" required minlength="6"></div>
      <div class="form-group">
        <label>角色</label>
        <select id="newAdminRole">
          <option value="admin">普通管理员</option>
          <option value="super_admin">超级管理员</option>
        </select>
      </div>
      <div class="modal-actions">
        <button type="button" class="btn btn-outline" onclick="closeModal()">取消</button>
        <button type="submit" class="btn btn-primary">创建</button>
      </div>
    </form>
  `;
  openModal();
}

async function handleAddAdmin(e) {
  e.preventDefault();
  try {
    await api('/api/config/admins', {
      method: 'POST',
      body: JSON.stringify({
        username: $('#newAdminUser').value,
        password: $('#newAdminPass').value,
        role: $('#newAdminRole').value
      })
    });
    showToast('管理员创建成功', 'success');
    closeModal();
    renderSettings();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

// ==================== APK 下载管理 ====================
async function renderDownloads() {
  const base = window.location.origin;
  $('#mainContent').innerHTML = `
    <div class="page-header">
      <h1>APK 下载管理</h1>
      <span style="color:var(--text-secondary);font-size:14px">用户可从服务器高速下载最新 APK</span>
    </div>
    <div class="card">
      <div class="card-header"><h3>📥 下载入口</h3></div>
      <div style="padding:16px;display:flex;flex-direction:column;gap:12px">
        <div style="display:flex;align-items:center;gap:16px;padding:12px;background:var(--bg-hover);border-radius:var(--radius)">
          <span style="font-size:24px">🐙</span>
          <div style="flex:1">
            <div style="font-weight:600;font-size:15px">从 GitHub 同步最新 APK</div>
            <div style="color:var(--text-secondary);font-size:13px" id="syncApkHint">自动从 GitHub Releases 拉取最新安装包到服务器</div>
          </div>
          <button class="btn btn-primary btn-sm" id="syncApkBtn" onclick="startApkSync()">同步</button>
        </div>
        <div id="apkSyncProgress" style="display:none;padding:12px;background:var(--bg-hover);border-radius:var(--radius)">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <span id="syncApkStage" style="font-weight:600;font-size:14px">准备中...</span>
            <span id="syncApkPercent" style="font-size:13px;color:var(--text-secondary)">0%</span>
          </div>
          <div style="height:8px;background:var(--border);border-radius:999px;overflow:hidden">
            <div id="syncApkBar" style="height:100%;width:0%;background:var(--primary);border-radius:999px;transition:width .3s"></div>
          </div>
          <div id="syncApkTask" style="margin-top:6px;color:var(--text-secondary);font-size:12px"></div>
        </div>
        <div style="display:flex;align-items:center;gap:16px;padding:12px;background:var(--bg-hover);border-radius:var(--radius)">
          <span style="font-size:24px">🌐</span>
          <div style="flex:1">
            <div style="font-weight:600;font-size:15px">网页管理端</div>
            <div style="color:var(--text-secondary);font-size:13px">管理员后台，无需安装，浏览器直接访问</div>
          </div>
          <button class="btn btn-primary btn-sm" onclick="copyLink('${base}')">复制链接</button>
          <a href="${base}" target="_blank" class="btn btn-outline btn-sm">打开</a>
        </div>
        <div style="display:flex;align-items:center;gap:16px;padding:12px;background:var(--bg-hover);border-radius:var(--radius)">
          <span style="font-size:24px">🌏</span>
          <div style="flex:1">
            <div style="font-weight:600;font-size:15px">用户端网页版</div>
            <div style="color:var(--text-secondary);font-size:13px">与 APP 同数据，浏览器直接查询开奖历史，无需安装</div>
            <div style="color:var(--primary);font-size:12px;margin-top:4px;word-break:break-all">${base}/web/</div>
          </div>
          <button class="btn btn-primary btn-sm" onclick="copyLink('${base}/web/')">复制链接</button>
          <a href="${base}/web/" target="_blank" class="btn btn-outline btn-sm">打开</a>
        </div>
        <div style="display:flex;align-items:center;gap:16px;padding:12px;background:var(--bg-hover);border-radius:var(--radius)" id="adminApkEntry">
          <span style="font-size:24px">🛠️</span>
          <div style="flex:1">
            <div style="font-weight:600;font-size:15px">管理端 APP</div>
            <div style="color:var(--text-secondary);font-size:13px" id="adminApkInfo">Android 管理端安装包（上传后自动显示）</div>
          </div>
          <span id="adminApkBtn"></span>
        </div>
        <div style="display:flex;align-items:center;gap:16px;padding:12px;background:var(--bg-hover);border-radius:var(--radius)" id="userApkEntry">
          <span style="font-size:24px">📱</span>
          <div style="flex:1">
            <div style="font-weight:600;font-size:15px">用户端 APP</div>
            <div style="color:var(--text-secondary);font-size:13px" id="userApkInfo">Android 用户端安装包（上传后自动显示）</div>
          </div>
          <span id="userApkBtn"></span>
        </div>
      </div>
    </div>
    <div class="card" style="margin-top:16px">
      <div class="card-header">
        <h3>📱 APK 文件列表</h3>
        <button class="btn btn-primary btn-sm" onclick="document.getElementById('apkFileInput').click()">上传 APK</button>
        <input type="file" id="apkFileInput" accept=".apk" style="display:none" onchange="handleApkUpload(this)">
      </div>
      <div class="table-container" id="apkTable"><div class="empty-state">加载中...</div></div>
    </div>
    <div class="card" style="margin-top:16px">
      <div class="card-header"><h3>🔗 下载链接</h3></div>
      <div style="padding:16px" id="apkLinks"><div class="empty-state">加载中...</div></div>
    </div>
  `;

  await loadApkList();
  initApkSyncView();
}

async function loadApkList() {
  try {
    // 并行拉取本地上传列表 + 最新 GitHub Release 的镜像映射
    const [files, mirrorMap] = await Promise.all([
      api('/api/apk-list'),
      loadApkMirrorMap()
    ]);
    const base = window.location.origin;

    if (!files.length) {
      $('#apkTable').innerHTML = '<div class="empty-state">暂无 APK 文件，请上传</div>';
      $('#apkLinks').innerHTML = '<div class="empty-state">暂无下载链接</div>';
      return;
    }

    // 每个文件的下载候选：优先镜像（GitHub 加速），依次回落原站与服务器本地
    const dlOf = (f) => {
      const m = mirrorMap[f.filename];
      if (m) return m.mirrors.concat([m.github, base + f.url]);
      return [base + f.url];
    };
    const firstDlOf = (f) => dlOf(f)[0];

    $('#apkTable').innerHTML = `
      <table>
        <thead><tr><th>文件名</th><th>大小</th><th>更新时间</th><th>下载链接</th><th>操作</th></tr></thead>
        <tbody>${files.map(f => `
          <tr>
            <td><strong>${f.filename}</strong>${mirrorMap[f.filename] ? ' <span class="badge badge-ok" style="font-size:11px">⚡加速</span>' : ''}</td>
            <td>${formatFileSize(f.size)}</td>
            <td>${formatDate(f.updatedAt)}</td>
            <td><a href="${firstDlOf(f)}" target="_blank" rel="noopener" style="color:var(--primary);word-break:break-all">${firstDlOf(f)}</a></td>
            <td>
              <button class="btn btn-sm btn-outline" onclick="copyLink('${firstDlOf(f)}')">复制链接</button>
              <button class="btn btn-sm btn-danger" onclick="deleteApk('${f.filename}')">删除</button>
            </td>
          </tr>
        `).join('')}</tbody>
      </table>
    `;

    $('#apkLinks').innerHTML = files.map(f => `
      <div style="display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid var(--border)">
        <span style="font-weight:600;min-width:200px">${f.filename}${mirrorMap[f.filename] ? ' ⚡' : ''}</span>
        <span style="color:var(--text-secondary);font-size:12px">${formatFileSize(f.size)}</span>
        <code style="font-size:12px;word-break:break-all;flex:1">${firstDlOf(f)}</code>
        <button class="btn btn-sm btn-outline" onclick="copyLink('${firstDlOf(f)}')">📋</button>
      </div>
    `).join('');

    // 匹配管理端和用户端 APK
    const adminApk = files.find(f => /admin|管理/i.test(f.filename)) || files[0];
    const userApk = files.find(f => /user|用户|app/i.test(f.filename) && !/admin|管理/i.test(f.filename)) || files[0];

    if (adminApk) {
      $('#adminApkInfo').textContent = `${adminApk.filename} (${formatFileSize(adminApk.size)})`;
      $('#adminApkBtn').innerHTML = `
        <button class="btn btn-primary btn-sm" onclick="copyLink('${firstDlOf(adminApk)}')">复制链接</button>
        <a href="${firstDlOf(adminApk)}" target="_blank" rel="noopener" class="btn btn-outline btn-sm" style="margin-left:8px">${mirrorMap[adminApk.filename] ? '⚡ 镜像下载' : '下载'}</a>
      `;
    }

    if (userApk) {
      $('#userApkInfo').textContent = `${userApk.filename} (${formatFileSize(userApk.size)})`;
      $('#userApkBtn').innerHTML = `
        <button class="btn btn-primary btn-sm" onclick="copyLink('${firstDlOf(userApk)}')">复制链接</button>
        <a href="${firstDlOf(userApk)}" target="_blank" rel="noopener" class="btn btn-outline btn-sm" style="margin-left:8px">${mirrorMap[userApk.filename] ? '⚡ 镜像下载' : '下载'}</a>
      `;
    }
  } catch (err) {
    $('#apkTable').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

// 拉取最新 GitHub Release 的镜像映射：filename -> {mirrors, github}
// 镜像不可达时静默返回空 map，下载链接自然回落到服务器本地 /downloads。
async function loadApkMirrorMap() {
  const map = {};
  try {
    const r = await fetch('/api/apk/releases');
    if (r.ok) {
      const p = await r.json();
      (p.assets || []).forEach(a => { map[a.name] = { mirrors: a.mirrors || [], github: a.githubUrl }; });
    }
  } catch (e) { /* 忽略，回落服务器本地下载 */ }
  return map;
}

function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// ==================== 从 GitHub 同步 APK（后台任务 + 动态进度） ====================
let apkSyncPollTimer = null;

async function startApkSync() {
  try {
    const btn = $('#syncApkBtn');
    await api('/api/apk/sync', { method: 'POST' });
    if (btn) { btn.disabled = true; btn.textContent = '同步中...'; }
    showToast('已触发从 GitHub 同步，正在进行...', 'info');
    $('#apkSyncProgress').style.display = 'block';
    // 立即轮询一次，随后固定间隔
    pollApkSync();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

async function pollApkSync() {
  try {
    const s = await api('/api/apk/sync-status');
    renderApkSync(s);
    if (s.running) {
      if (!apkSyncPollTimer) {
        apkSyncPollTimer = setInterval(() => {
          api('/api/apk/sync-status').then(renderApkSync).catch(() => {});
        }, 1200);
      }
      // 完成时终止轮询
      monitorApkSyncDone();
    } else {
      stopApkSyncPoll();
    }
  } catch (err) {
    // 忽略轮询错误
  }
}

function monitorApkSyncDone() {
  const check = setInterval(async () => {
    try {
      const s = await api('/api/apk/sync-status');
      if (!s.running) {
        clearInterval(check);
        stopApkSyncPoll();
        renderApkSync(s);
        // 同步完成，刷新文件列表
        loadApkList();
      }
    } catch (e) {}
  }, 1500);
}

function stopApkSyncPoll() {
  if (apkSyncPollTimer) {
    clearInterval(apkSyncPollTimer);
    apkSyncPollTimer = null;
  }
  const btn = $('#syncApkBtn');
  if (btn) { btn.disabled = false; btn.textContent = '同步'; }
}

function renderApkSync(s) {
  const progress = $('#apkSyncProgress');
  if (!progress) return;
  if (!s.running && s.stage === 'idle') { progress.style.display = 'none'; return; }
  progress.style.display = 'block';

  const active = s.tasks.filter(t => t.status !== 'pending' && t.status !== 'done' && t.status !== 'error');
  const doneTask = s.tasks.find(t => t.status === 'done') || {};
  const current = active[0] || doneTask || s.tasks[0] || {};

  // 计算整体进度（各任务加权平均）
  let total = 0, received = 0;
  s.tasks.forEach(t => { total += (t.total || 0); received += (t.received || 0); });
  const percent = total > 0 ? Math.round(received / total * 100) : (s.stage === 'done' ? 100 : 0);

  $('#syncApkBar').style.width = percent + '%';
  $('#syncApkPercent').textContent = percent + '%';

  let stageText = '准备中...';
  if (s.error) {
    stageText = `❌ 同步失败：${s.error}`;
    $('#syncApkBtn').textContent = '重试';
  } else if (s.running && s.stage === 'fetching_release') {
    stageText = '⏳ 正在获取 GitHub Release 信息...';
  } else if (s.running && s.stage === 'downloading') {
    const cur = active[0];
    stageText = cur ? `⏳ 正在下载 ${cur.filename}` : '⏳ 正在下载...';
  } else if (s.stage === 'done') {
    stageText = `✅ ${s.tag ? '[' + s.tag + '] ' : ''}同步完成，共 ${s.tasks.length} 个 APK`;
  }
  $('#syncApkStage').textContent = stageText;

  const taskHtml = s.tasks.map(t => {
    const p = t.size > 0 ? Math.round((t.received || 0) / t.size * 100) : 0;
    const icon = t.status === 'done' ? '✅' : t.status === 'error' ? '❌' : t.status === 'downloading' ? '⏳' : '⬜';
    return `${icon} ${t.filename} — ${p}%`;
  }).join('<br>');
  $('#syncApkTask').innerHTML = taskHtml;
}

// 进入下载页时恢复同步状态显示
async function initApkSyncView() {
  try {
    const s = await api('/api/apk/sync-status');
    if (s.running || s.stage === 'done' || s.stage === 'error') {
      renderApkSync(s);
      if (s.running) pollApkSync();
    }
  } catch (e) {}
}

function copyLink(url) {
  navigator.clipboard.writeText(url).then(() => {
    showToast('链接已复制到剪贴板', 'success');
  }).catch(() => {
    showToast('复制失败，请手动复制', 'error');
  });
}

async function handleApkUpload(input) {
  const file = input.files[0];
  if (!file) return;
  if (!file.name.endsWith('.apk')) {
    showToast('仅支持 .apk 文件', 'error');
    input.value = '';
    return;
  }

  const formData = new FormData();
  formData.append('apk', file);

  try {
    showToast('正在上传...', 'info');
    const headers = {};
    if (API.token) headers['Authorization'] = `Bearer ${API.token}`;
    const res = await fetch(API.base + '/api/upload-apk', {
      method: 'POST',
      headers,
      body: formData
    });
    if (res.status === 401) {
      localStorage.removeItem('admin_token');
      showLogin();
      throw new Error('登录已过期');
    }
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '上传失败');
    showToast(`${file.name} 上传成功`, 'success');
    input.value = '';
    await loadApkList();
  } catch (err) {
    showToast(err.message, 'error');
    input.value = '';
  }
}

async function deleteApk(filename) {
  if (!confirm(`确定要删除 ${filename} 吗？`)) return;
  try {
    await api('/api/config/apk-delete', {
      method: 'POST',
      body: JSON.stringify({ filename })
    });
    showToast('已删除', 'success');
    await loadApkList();
  } catch (err) {
    showToast(err.message, 'error');
  }
}
function showForceChangePasswordModal() {
  $('#modalContent').innerHTML = `
    <h3 style="color:var(--danger)">⚠️ 安全要求：必须修改默认密码</h3>
    <p style="color:var(--text-secondary);margin:12px 0">
      当前仍在使用初始密码，首次登录后必须修改。未修改前无法使用其他功能，且不能关闭此窗口。
    </p>
    <form onsubmit="handleForceChangePassword(event)">
      <div class=\"form-group\">
        <label>原密码 <span style=\"color:red\">*</span></label>
        <input type=\"password\" id=\"fc_old\" required placeholder=\"请输入当前密码\">
      </div>
      <div class=\"form-group\">
        <label>新密码 <span style=\"color:red\">*</span>（至少8位，建议含大小写+数字）</label>
        <input type=\"password\" id=\"fc_new\" required minlength=\"8\">
      </div>
      <div class=\"form-group\">
        <label>确认新密码 <span style=\"color:red\">*</span></label>
        <input type=\"password\" id=\"fc_new2\" required minlength=\"8\">
      </div>
      <div class=\"modal-actions\">
        <button type=\"submit\" class=\"btn btn-danger\">确认修改密码</button>
      </div>
    </form>
  `;
  // 强制弹出，不可关闭
  openModal();
  // 阻止点击遮罩关闭
  $('#modalOverlay').onclick = (e) => { if(e.target.id !== 'modalContent' && !e.target.closest('.modal')) e.stopPropagation(); };
}

async function handleForceChangePassword(e) {
  e.preventDefault();
  const oldPwd = $('#fc_old').value;
  const newPwd = $('#fc_new').value;
  const newPwd2 = $('#fc_new2').value;

  if (newPwd.length < 8) {
    showToast('新密码至少8位', 'error'); return;
  }
  if (newPwd === oldPwd) {
    showToast('新密码不能与原密码相同', 'error'); return;
  }
  if (newPwd === 'admin123') {
    showToast('不能再使用 admin123 作为密码', 'error'); return;
  }
  if (newPwd !== newPwd2) {
    showToast('两次输入的新密码不一致', 'error'); return;
  }

  try {
    await api('/api/auth/change-password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword: oldPwd, newPassword: newPwd })
    });
    // 更新本地缓存 mustChangePassword 标记
    const admin = JSON.parse(localStorage.getItem('admin_user') || '{}');
    admin.mustChangePassword = false;
    localStorage.setItem('admin_user', JSON.stringify(admin));
    // 恢复遮罩点击关闭
    $('#modalOverlay').onclick = (e) => { if (e.target === $('#modalOverlay')) closeModal(); };
    showToast('密码修改成功！请务必记住新密码', 'success');
    closeModal();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

function openModal() { $('#modalOverlay').classList.add('show'); }
function closeModal() { $('#modalOverlay').classList.remove('show'); }

// ==================== 初始化 ====================
document.addEventListener('DOMContentLoaded', () => {
  const token = localStorage.getItem('admin_token');
  const admin = localStorage.getItem('admin_user');
  if (token && admin) {
    API.token = token;
    try { showApp(JSON.parse(admin)); } catch (e) { showLogin(); }
  } else {
    showLogin();
  }
});