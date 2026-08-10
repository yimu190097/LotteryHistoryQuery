/**
 * 彩票后台管理系统 - 前端应用
 * 纯原生JS，无框架依赖，兼容所有现代浏览器
 */

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
    case 'audit': renderAuditLog(); break;
    case 'settings': renderSettings(); break;
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

    $('#statsGrid').innerHTML = `
      <div class="stat-card">
        <div class="stat-value">${s.totalUsers}</div>
        <div class="stat-label">总用户数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">${s.todayNewUsers}</div>
        <div class="stat-label">今日新增</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">${s.totalQueries}</div>
        <div class="stat-label">总查询次数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">${s.todayQueries}</div>
        <div class="stat-label">今日查询</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">${s.quotaStats?.total || 0}</div>
        <div class="stat-label">付费用户</div>
      </div>
      <div class="stat-card">
        <div class="stat-value">${s.quotaStats?.monthly_count || 0}</div>
        <div class="stat-label">月租用户</div>
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
let userPage = 1, userSearch = '';
async function renderUsers() {
  $('#mainContent').innerHTML = `
    <div class="page-header" style="display:flex;justify-content:space-between;align-items:center">
      <h1>用户管理</h1>
      <button class="btn btn-success" onclick="showRegisterUserModal()">+ 注册用户</button>
    </div>
    <div class="card">
      <div class="search-bar">
        <input type="text" id="userSearch" placeholder="搜索手机号或昵称..." value="${userSearch}"
          onkeydown="if(event.key==='Enter'){userSearch=this.value;userPage=1;renderUsers()}">
        <button class="btn btn-primary" onclick="userSearch=$('#userSearch').value;userPage=1;renderUsers()">搜索</button>
      </div>
      <div class="table-container" id="userTable"><div class="empty-state">加载中...</div></div>
      <div id="userPagination"></div>
    </div>
  `;

  try {
    const data = await api(`/api/users?page=${userPage}&size=20&search=${encodeURIComponent(userSearch)}`);
    if (!data.data.length) {
      $('#userTable').innerHTML = '<div class="empty-state">暂无用户数据</div>';
      return;
    }

    $('#userTable').innerHTML = `
      <table>
        <thead><tr>
          <th>手机号</th><th>昵称</th><th>套餐类型</th><th>剩余次数</th>
          <th>到期时间</th><th>注册时间</th><th>操作</th>
        </tr></thead>
        <tbody>${data.data.map(u => `
          <tr>
            <td>${u.phone}</td>
            <td>${u.nickname || '—'}</td>
            <td><span class="badge ${u.plan_type === 'MONTHLY' ? 'badge-success' : 'badge-info'}">${u.plan_type === 'MONTHLY' ? '月租' : '按次'}</span></td>
            <td style="font-weight:600;color:${u.remaining_queries > 0 ? '#2E7D32' : '#C62828'}">${u.remaining_queries ?? 0}</td>
            <td>${u.monthly_expire_at ? formatDateShort(u.monthly_expire_at) : '—'}</td>
            <td>${formatDateShort(u.created_at)}</td>
            <td>
              <button class="btn btn-outline btn-sm" onclick="showUserDetail('${u.phone}')">详情</button>
              <button class="btn btn-primary btn-sm" onclick="showQuotaModal('${u.phone}')">配额</button>
            </td>
          </tr>
        `).join('')}</tbody>
      </table>
    `;

    renderPagination($('#userPagination'), data, (p) => { userPage = p; renderUsers(); });
  } catch (err) {
    $('#userTable').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

function renderPagination(container, data, onPage) {
  if (data.totalPages <= 1) { container.innerHTML = ''; return; }
  let html = '';
  html += `<button ${data.page <= 1 ? 'disabled' : ''} onclick="void(0)">«</button>`;
  for (let i = 1; i <= data.totalPages; i++) {
    if (i === 1 || i === data.totalPages || Math.abs(i - data.page) <= 2) {
      html += `<button class="${i === data.page ? 'active' : ''}" onclick="void(0)">${i}</button>`;
    } else if (i === 2 || i === data.totalPages - 1) {
      html += '<button disabled>...</button>';
    }
  }
  html += `<button ${data.page >= data.totalPages ? 'disabled' : ''} onclick="void(0)">»</button>`;
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
    $('#modalContent').innerHTML = `
      <h3>用户详情 - ${u.phone}</h3>
      <div class="form-group"><label>手机号</label><input value="${u.phone}" readonly></div>
      <div class="form-group"><label>昵称</label><input value="${u.nickname || '—'}" readonly></div>
      <div class="form-group"><label>套餐类型</label><input value="${u.plan_type === 'MONTHLY' ? '月租用户' : '按次用户'}" readonly></div>
      <div class="form-group"><label>剩余查询次数</label><input value="${u.remaining_queries ?? 0}" readonly></div>
      <div class="form-group"><label>月租到期</label><input value="${u.monthly_expire_at ? formatDate(u.monthly_expire_at) : '—'}" readonly></div>
      <div class="form-group"><label>注册时间</label><input value="${formatDate(u.created_at)}" readonly></div>
      <div class="modal-actions">
        <button class="btn btn-outline" onclick="closeModal()">关闭</button>
        <button class="btn btn-primary" onclick="closeModal();showQuotaModal('${u.phone}')">修改配额</button>
      </div>
    `;
    openModal();
  } catch (err) {
    showToast(err.message, 'error');
  }
}

function showQuotaModal(phone) {
  $('#modalContent').innerHTML = `
    <h3>设置配额 - ${phone}</h3>
    <form onsubmit="handleSetQuota(event, '${phone}')">
      <div class="form-group">
        <label>套餐类型</label>
        <select id="quotaPlanType">
          <option value="PAY_PER_USE">按次付费</option>
          <option value="MONTHLY">月租用户</option>
        </select>
      </div>
      <div class="form-group">
        <label>剩余查询次数</label>
        <input type="number" id="quotaRemaining" value="10" min="0" required>
      </div>
      <div class="form-group">
        <label>月租到期时间（月租用户填写，Unix时间戳毫秒）</label>
        <input type="number" id="quotaExpireAt" value="" placeholder="留空不修改">
      </div>
      <div class="modal-actions">
        <button type="button" class="btn btn-outline" onclick="closeModal()">取消</button>
        <button type="submit" class="btn btn-primary">保存</button>
      </div>
    </form>
  `;
  openModal();
}

async function handleSetQuota(e, phone) {
  e.preventDefault();
  const planType = $('#quotaPlanType').value;
  const remainingQueries = parseInt($('#quotaRemaining').value);
  const monthlyExpireAt = $('#quotaExpireAt').value ? parseInt($('#quotaExpireAt').value) : undefined;

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

// ==================== 注册用户 ====================
function showRegisterUserModal() {
  $('#modalContent').innerHTML = `
    <h3>注册新用户</h3>
    <form onsubmit="handleRegisterUser(event)">
      <div class="form-group">
        <label>手机号 <span style="color:red">*</span></label>
        <input type="text" id="regPhone" placeholder="请输入手机号" required>
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

// ==================== 弹窗 ====================
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