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
          <th>到期时间</th><th>注册时间</th><th>操作</th>
        </tr></thead>
        <tbody>${data.data.map(u => {
          const isMonthly = u.plan_type === 'MONTHLY';
          const isExpired = isMonthly && u.monthly_expire_at && u.monthly_expire_at < now;
          const statusText = isMonthly ? (isExpired ? '已过期' : '正常') : '—';
          const statusBadge = isMonthly ? (isExpired ? 'badge-danger' : 'badge-success') : 'badge-info';
          const quotaColor = (u.remaining_queries ?? 0) > 0 ? '#2E7D32' : '#C62828';
          return `
          <tr>
            <td><strong>${u.phone}</strong></td>
            <td>${u.nickname || '—'}</td>
            <td><span class="badge ${isMonthly ? 'badge-success' : 'badge-info'}">${isMonthly ? '月租' : '按次'}</span></td>
            <td><span class="badge ${statusBadge}">${statusText}</span></td>
            <td style="font-weight:600;color:${quotaColor}">${u.remaining_queries ?? 0}</td>
            <td>${u.monthly_expire_at ? formatDateShort(u.monthly_expire_at) : '—'}</td>
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
  $('#mainContent').innerHTML = `
    <div class="page-header">
      <h1>APK 下载管理</h1>
      <span style="color:var(--text-secondary);font-size:14px">用户可从服务器高速下载最新 APK</span>
    </div>
    <div class="card">
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
}

async function loadApkList() {
  try {
    const files = await api('/api/apk-list');
    const base = window.location.origin;

    if (!files.length) {
      $('#apkTable').innerHTML = '<div class="empty-state">暂无 APK 文件，请上传</div>';
      $('#apkLinks').innerHTML = '<div class="empty-state">暂无下载链接</div>';
      return;
    }

    $('#apkTable').innerHTML = `
      <table>
        <thead><tr><th>文件名</th><th>大小</th><th>更新时间</th><th>下载链接</th><th>操作</th></tr></thead>
        <tbody>${files.map(f => `
          <tr>
            <td><strong>${f.filename}</strong></td>
            <td>${formatFileSize(f.size)}</td>
            <td>${formatDate(f.updatedAt)}</td>
            <td><a href="${base}${f.url}" target="_blank" style="color:var(--primary)">${base}${f.url}</a></td>
            <td>
              <button class="btn btn-sm btn-outline" onclick="copyLink('${base}${f.url}')">复制链接</button>
              <button class="btn btn-sm btn-danger" onclick="deleteApk('${f.filename}')">删除</button>
            </td>
          </tr>
        `).join('')}</tbody>
      </table>
    `;

    $('#apkLinks').innerHTML = files.map(f => `
      <div style="display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid var(--border)">
        <span style="font-weight:600;min-width:200px">${f.filename}</span>
        <span style="color:var(--text-secondary);font-size:12px">${formatFileSize(f.size)}</span>
        <code style="font-size:12px;word-break:break-all;flex:1">${base}${f.url}</code>
        <button class="btn btn-sm btn-outline" onclick="copyLink('${base}${f.url}')">📋</button>
      </div>
    `).join('');
  } catch (err) {
    $('#apkTable').innerHTML = `<div class="empty-state">加载失败: ${err.message}</div>`;
  }
}

function formatFileSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
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
