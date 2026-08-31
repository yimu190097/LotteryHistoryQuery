const express = require('express');
const { db } = require('../db/database');
const { authMiddleware, userAuthMiddleware } = require('../middleware/auth');
const path = require('path');
const fs = require('fs');
const router = express.Router();
const DOWNLOADS_DIR = path.join(__dirname, '..', '..', 'public', 'downloads');
const ICE_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' }
];
if (process.env.TURN_HOST) {
  const turnUser = process.env.TURN_USER || 'lottery';
  const turnPass = process.env.TURN_PASS || 'lottery2026';
  ICE_SERVERS.push({
    urls: `turn:${process.env.TURN_HOST}:3478`,
    username: turnUser,
    credential: turnPass
  });
}let configCache = null;
let cacheTime = 0;
const CACHE_TTL = 5 * 60 * 1000;
router.get('/ice', authMiddleware, (req, res) => {
  res.json({ iceServers: ICE_SERVERS });
});
router.get('/', (req, res) => {
  if (configCache && Date.now() - cacheTime < CACHE_TTL) {
    return res.json(configCache);
  }
  const configs = db.prepare('SELECT * FROM system_config').all();
  const result = {};
  configs.forEach(c => { result[c.key] = c.value; });
  configCache = result;
  cacheTime = Date.now();
  res.json(result);
});
// P0 鉴权加固：以下会写配置 / 管理管理员的接口必须管理员登录。
// （此前 PUT /:key 与 GET/POST /admins 完全无鉴权，任何人都能改配置/建管理员）
router.put('/:key', authMiddleware, (req, res) => {
  const { key } = req.params;
  const { value } = req.body;
  if (value === undefined || value === null) { return res.status(400).json({ error: '值不能为空' }); }
  db.prepare('INSERT OR REPLACE INTO system_config (key, value, updated_at) VALUES (?, ?, ?)')
    .run(key, String(value), Date.now());
  configCache = null;
  db.prepare('INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .run(req.admin.id, req.admin.username, 'UPDATE_CONFIG', key, String(value), Date.now());
  res.json({ message: '配置更新成功' });
});
router.get('/admins', authMiddleware, (req, res) => {
  const admins = db.prepare('SELECT id, username, role, created_at, last_login FROM admins ORDER BY id').all();
  res.json(admins);
});
router.post('/admins', authMiddleware, (req, res) => {
  const { username, password, role } = req.body;
  if (!username || !password) { return res.status(400).json({ error: '用户名和密码不能为空' }); }
  const existing = db.prepare('SELECT id FROM admins WHERE username = ?').get(username);
  if (existing) { return res.status(400).json({ error: '管理员已存在' }); }
  const bcrypt = require('bcryptjs');
  const hash = bcrypt.hashSync(password, 10);
  db.prepare('INSERT INTO admins (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)')
    .run(username, hash, role || 'admin', Date.now());
  db.prepare('INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .run(req.admin.id, req.admin.username, 'CREATE_ADMIN', username, '', Date.now());
  res.json({ message: '管理员创建成功' });
});
router.post('/apk-delete', authMiddleware, (req, res) => {
  const { filename } = req.body;
  if (!filename) return res.status(400).json({ error: '文件名不能为空' });
  const safeName = path.basename(filename);
  if (!safeName.endsWith('.apk')) return res.status(400).json({ error: '仅支持删除 .apk 文件' });
  const filePath = path.join(DOWNLOADS_DIR, safeName);
  if (!fs.existsSync(filePath)) return res.status(404).json({ error: '文件不存在' });
  fs.unlinkSync(filePath);
  db.prepare('INSERT INTO audit_log (admin_id, admin_username, action, target, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .run(req.admin.id, req.admin.username, 'DELETE_APK', safeName, '删除APK文件', Date.now());
  res.json({ message: 'APK 已删除' });
});
module.exports = router;