const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');
const DB_PATH = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'admin.db');
const dataDir = path.dirname(DB_PATH);
if (!fs.existsSync(dataDir)) { fs.mkdirSync(dataDir, { recursive: true }); }
const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

function initTables() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS admins (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      role TEXT NOT NULL DEFAULT 'admin',
      must_change_password INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL,
      last_login INTEGER
    );
    CREATE TABLE IF NOT EXISTS users (
      phone TEXT PRIMARY KEY,
      password_hash TEXT NOT NULL,
      nickname TEXT,
      is_admin INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS quotas (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_phone TEXT NOT NULL UNIQUE,
      plan_type TEXT NOT NULL DEFAULT 'FREE',
      remaining_queries INTEGER NOT NULL DEFAULT 0,
      monthly_expire_at INTEGER,
      server_version INTEGER NOT NULL DEFAULT 0,
      local_version INTEGER NOT NULL DEFAULT 0,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (user_phone) REFERENCES users(phone)
    );    CREATE TABLE IF NOT EXISTS pending_sync (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_phone TEXT NOT NULL,
      action_type TEXT NOT NULL,
      payload TEXT NOT NULL,
      client_op_id TEXT NOT NULL UNIQUE,
      status TEXT NOT NULL DEFAULT 'PENDING',
      retry_count INTEGER NOT NULL DEFAULT 0,
      last_error TEXT,
      created_at INTEGER NOT NULL,
      synced_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS idempotency_log (
      op_id TEXT PRIMARY KEY,
      user_phone TEXT NOT NULL,
      action TEXT NOT NULL,
      result_payload TEXT,
      created_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_idempotency_log_user ON idempotency_log(user_phone, created_at DESC);
    CREATE TABLE IF NOT EXISTS audit_log (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      admin_id INTEGER,
      admin_username TEXT,
      action TEXT NOT NULL,
      target TEXT,
      detail TEXT,
      created_at INTEGER NOT NULL,
      FOREIGN KEY (admin_id) REFERENCES admins(id)
    );
    CREATE TABLE IF NOT EXISTS system_config (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      updated_at INTEGER NOT NULL
    );    CREATE TABLE IF NOT EXISTS chat_sessions (
      user_phone TEXT PRIMARY KEY, user_nickname TEXT, last_message TEXT,
      last_message_at INTEGER, last_message_type TEXT,
      user_unread INTEGER NOT NULL DEFAULT 0, admin_unread INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
      FOREIGN KEY (user_phone) REFERENCES users(phone)
    );
    CREATE TABLE IF NOT EXISTS chat_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT, session_user_phone TEXT NOT NULL,
      role TEXT NOT NULL, type TEXT NOT NULL, text TEXT, media_path TEXT,
      duration INTEGER, created_at INTEGER NOT NULL,
      FOREIGN KEY (session_user_phone) REFERENCES chat_sessions(user_phone)
    );
    CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_user_phone, created_at);
    CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated ON chat_sessions(updated_at DESC);
    CREATE INDEX IF NOT EXISTS idx_audit_log_action ON audit_log(action, created_at);
    CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);
    CREATE TABLE IF NOT EXISTS user_sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_phone TEXT NOT NULL,
      jti TEXT NOT NULL UNIQUE,
      device_info TEXT,
      ip_address TEXT,
      created_at INTEGER NOT NULL,
      last_active_at INTEGER NOT NULL,
      FOREIGN KEY (user_phone) REFERENCES users(phone)
    );
    CREATE INDEX IF NOT EXISTS idx_user_sessions_phone ON user_sessions(user_phone);
    CREATE TABLE IF NOT EXISTS vip_plans (
      plan_type TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      price REAL NOT NULL DEFAULT 0,
      duration_days INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );
  `);  try {
    db.prepare('ALTER TABLE admins ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0').run();
    console.log('[DB Migration] 已补列: admins.must_change_password');
  } catch (_) {}
  try {
    db.prepare('ALTER TABLE quotas ADD COLUMN free_query_date INTEGER NOT NULL DEFAULT 0').run();
    console.log('[DB Migration] 已补列: quotas.free_query_date');
  } catch (_) {}
  try {
    const col = db.prepare("PRAGMA table_info('admins')").all();
    console.log(`[DB Migration] admins 列数=${col.length}`);
  } catch (_) {}
  const bcrypt = require('bcryptjs');
  const existing = db.prepare('SELECT id FROM admins WHERE username = ?').get('admin');
  // 初始管理员密码：优先取 .env 的 ADMIN_PASS（deploy.sh 已随机化），否则回退 admin123 并强制首登改密
  const envPass = process.env.ADMIN_PASS;
  const seedPass = (envPass && envPass !== 'admin123' && envPass !== 'changeme') ? envPass : 'admin123';
  if (!existing) {
    const hash = bcrypt.hashSync(seedPass, 10);
    db.prepare('INSERT INTO admins (username, password_hash, role, must_change_password, created_at) VALUES (?, ?, ?, 1, ?)')
      .run('admin', hash, 'super_admin', Date.now());
    console.log(`[DB] 默认管理员已创建: admin / ${envPass ? '来自 .env 的 ADMIN_PASS' : 'admin123'} — 首次登录后必须修改密码`);
  } else {
    const admin = db.prepare('SELECT password_hash FROM admins WHERE username = ?').get('admin');
    if (admin && bcrypt.compareSync('admin123', admin.password_hash)) {
      db.prepare('UPDATE admins SET must_change_password = 1 WHERE username = ?').run('admin');
      console.log('[DB] 默认密码仍为 admin123，已强制标记 must_change_password=1');
    }
  }  const defaultConfigs = {
    'app_version': '23.5', 'free_query_limit': '2',
    'audit_log_retention_days': '30'
  };
  const insertConfig = db.prepare(
    'INSERT OR IGNORE INTO system_config (key, value, updated_at) VALUES (?, ?, ?)'
  );
  for (const [key, value] of Object.entries(defaultConfigs)) {
    insertConfig.run(key, value, Date.now());
  }

  // 种子 VIP 套餐（如不存在）
  const vipPlans = [
    { type: 'MONTHLY_VIP', name: '月VIP', price: 30, days: 30 },
    { type: 'QUARTERLY_VIP', name: '季VIP', price: 80, days: 90 },
    { type: 'SEMI_ANNUAL_VIP', name: '半年VIP', price: 150, days: 180 },
    { type: 'ANNUAL_VIP', name: '年VIP', price: 280, days: 365 }
  ];
  const insertVip = db.prepare(
    'INSERT OR IGNORE INTO vip_plans (plan_type, name, price, duration_days, updated_at) VALUES (?, ?, ?, ?, ?)'
  );
  for (const p of vipPlans) {
    insertVip.run(p.type, p.name, p.price, p.days, Date.now());
  }

  // 创建测试账号（如不存在）
  const testPhone = '13800138000';
  const testUser = db.prepare('SELECT phone FROM users WHERE phone = ?').get(testPhone);
  if (!testUser) {
    const testHash = bcrypt.hashSync('test123456', 10);
    const now = Date.now();
    db.prepare(
      'INSERT INTO users (phone, password_hash, nickname, is_admin, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?)'
    ).run(testPhone, testHash, '测试用户', now, now);
    db.prepare(
      'INSERT INTO quotas (user_phone, plan_type, remaining_queries, monthly_expire_at, server_version, local_version, updated_at) VALUES (?, ?, ?, NULL, 1, 0, ?)'
    ).run(testPhone, 'FREE', 0, now);
    console.log(`[DB] 测试账号已创建: ${testPhone} / test123456`);
  }
  console.log('[DB] 数据库初始化完成');
}function cleanAuditLog() {
  try {
    const row = db.prepare("SELECT value FROM system_config WHERE key = 'audit_log_retention_days'").get();
    const retentionDays = parseInt(row?.value) || 30;
    const cutoff = Date.now() - retentionDays * 24 * 60 * 60 * 1000;
    const result = db.prepare('DELETE FROM audit_log WHERE created_at < ?').run(cutoff);
    if (result.changes > 0) {
      console.log(`[DB Cleanup] ${result.changes} 条超过 ${retentionDays} 天的审计日志`);
    }
  } catch (e) {
    console.error('[DB Cleanup] 审计日志清理失败:', e.message);
  }
  // 清理 7 天前的幂等日志（足够覆盖 WorkManager 重试窗口）
  try {
    const idemCutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
    const r = db.prepare('DELETE FROM idempotency_log WHERE created_at < ?').run(idemCutoff);
    if (r.changes > 0) {
      console.log(`[DB Cleanup] ${r.changes} 条过期幂等日志`);
    }
  } catch (e) {
    console.error('[DB Cleanup] 幂等日志清理失败:', e.message);
  }
}
setTimeout(cleanAuditLog, 10000);
setInterval(cleanAuditLog, 24 * 60 * 60 * 1000);
module.exports = { db, initTables };