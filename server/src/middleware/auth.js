const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'lottery-admin-secret-key-2026';
const JWT_EXPIRES = '24h';

/**
 * 生成 JWT Token
 */
function generateToken(admin) {
  return jwt.sign(
    { id: admin.id, username: admin.username, role: admin.role },
    JWT_SECRET,
    { expiresIn: JWT_EXPIRES }
  );
}

/**
 * 验证 JWT Token 中间件
 */
function authMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: '未登录或Token已过期' });
  }

  const token = authHeader.substring(7);
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    req.admin = decoded;
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Token无效或已过期，请重新登录' });
  }
}

/**
 * 超级管理员权限中间件
 */
function superAdminOnly(req, res, next) {
  if (req.admin.role !== 'super_admin') {
    return res.status(403).json({ error: '需要超级管理员权限' });
  }
  next();
}

module.exports = { generateToken, authMiddleware, superAdminOnly, JWT_SECRET };