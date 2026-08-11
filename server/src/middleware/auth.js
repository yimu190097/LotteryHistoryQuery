const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'lottery-admin-secret-key-2026';
const JWT_EXPIRES = '24h';

// 启动时安全告警：JWT_SECRET 仍为默认值
if (!process.env.JWT_SECRET) {
  console.warn('\x1b[33m[WARN] JWT_SECRET 未设置环境变量，正在使用默认值！生产部署必须 export JWT_SECRET=<随机长串>\x1b[0m');
}

/**
 * 生成管理员 JWT Token（含 role 字段）
 */
function generateToken(admin) {
  return jwt.sign(
    { id: admin.id, username: admin.username, role: admin.role },
    JWT_SECRET,
    { expiresIn: JWT_EXPIRES }
  );
}

/**
 * 生成客户端用户 JWT Token（不含 role 字段，便于 chatServer 区分）
 */
function generateUserToken(user) {
  return jwt.sign(
    { phone: user.phone, nickname: user.nickname, isUser: true },
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

/**
 * 客户端用户鉴权中间件：校验 token 且必须含 isUser:true
 * 验证后将 req.user 设为 { phone, nickname }
 */
function userAuthMiddleware(req, res, next) {
  const authHeader = req.headers.authorization;
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: '未登录或Token已过期' });
  }
  const token = authHeader.substring(7);
  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    if (!decoded.isUser || !decoded.phone) {
      return res.status(401).json({ error: 'Token 类型错误' });
    }
    req.user = { phone: decoded.phone, nickname: decoded.nickname };
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Token无效或已过期，请重新登录' });
  }
}

module.exports = { generateToken, generateUserToken, authMiddleware, superAdminOnly, userAuthMiddleware, JWT_SECRET };