const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, '..', 'data', 'admin.db');

// 确保数据目录存在
const dataDir = path.dirname(DB_PATH);
if (!fs.existsSync(dataDir)) {
  fs.mkdirSync(dataDir, { recursive: true });
}

const db = new Database(DB_PATH);

// 启用 WAL 模式提升并发性能
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

// ========== 初始化表结构 ==========
function initTables() {
  db.exec(`
    -- 管理员表
    CREATE TABLE IF NOT EXISTS admins (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      role TEXT NOT NULL DEFAULT 'admin',
      created_at INTEGER NOT NULL,
      last_login INTEGER
    );

    -- 客户用户表（与App端UserEntity对应）
    CREATE TABLE IF NOT EXISTS users (
      phone TEXT PRIMARY KEY,
      password_hash TEXT NOT NULL,
      nickname TEXT,
      is_admin INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    );

    -- 配额表（与App端QuotaEntity对应）
    CREATE TABLE IF NOT EXISTS quotas (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_phone TEXT NOT NULL UNIQUE,
      plan_type TEXT NOT NULL DEFAULT 'PAY_PER_USE',
      remaining_queries INTEGER NOT NULL DEFAULT 0,
      monthly_expire_at INTEGER,
      server_version INTEGER NOT NULL DEFAULT 0,
      local_version INTEGER NOT NULL DEFAULT 0,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (user_phone) REFERENCES users(phone)
    );

    -- 同步队列（与App端pending_sync对应）
    CREATE TABLE IF NOT EXISTS pending_sync (
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

    -- 操作日志
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

    -- 系统配置
    CREATE TABLE IF NOT EXISTS system_config (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      updated_at INTEGER NOT NULL
    );

    -- 客服会话表：每个用户一个会话，记录最新消息和未读数
    CREATE TABLE IF NOT EXISTS chat_sessions (
      user_phone TEXT PRIMARY KEY,
      user_nickname TEXT,
      last_message TEXT,
      last_message_at INTEGER,
      last_message_type TEXT,
      user_unread INTEGER NOT NULL DEFAULT 0,
      admin_unread INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      FOREIGN KEY (user_phone) REFERENCES users(phone)
    );

    -- 客服消息表：存储所有聊天消息（文字/图片/语音）
    CREATE TABLE IF NOT EXISTS chat_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_user_phone TEXT NOT NULL,
      role TEXT NOT NULL,              -- 'SENT' (用户发送) | 'RECEIVED' (管理员回复)
      type TEXT NOT NULL,              -- 'TEXT' | 'IMAGE' | 'VOICE'
      text TEXT,
      media_path TEXT,                 -- 图片/语音文件的 URL 路径
      duration INTEGER,                -- 语音时长（秒）
      created_at INTEGER NOT NULL,
      FOREIGN KEY (session_user_phone) REFERENCES chat_sessions(user_phone)
    );

    CREATE INDEX IF NOT EXISTS idx_chat_messages_session ON chat_messages(session_user_phone, created_at);
    CREATE INDEX IF NOT EXISTS idx_chat_sessions_updated ON chat_sessions(updated_at DESC);
  `);

  // 插入默认管理员（admin/admin123）
  const bcrypt = require('bcryptjs');
  const existing = db.prepare('SELECT id FROM admins WHERE username = ?').get('admin');
  if (!existing) {
    const hash = bcrypt.hashSync('admin123', 10);
    db.prepare('INSERT INTO admins (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)')
      .run('admin', hash, 'super_admin', Date.now());
    console.log('[DB] 默认管理员已创建: admin / admin123');
  }

  // 默认系统配置
  const defaultConfigs = {
    'app_version': '23.5',
    'free_quota': '10',
    'query_price': '1',
    'monthly_price': '30',
    'annual_price': '300'
  };
  const insertConfig = db.prepare(
    'INSERT OR IGNORE INTO system_config (key, value, updated_at) VALUES (?, ?, ?)'
  );
  for (const [key, value] of Object.entries(defaultConfigs)) {
    insertConfig.run(key, value, Date.now());
  }

  console.log('[DB] 数据库初始化完成');
}

module.exports = { db, initTables };