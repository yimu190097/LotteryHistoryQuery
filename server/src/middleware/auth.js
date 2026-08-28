const jwt = require('jsonwebtoken');

const DEFAULT_JWT_SECRET = 'lottery-admin-secret-key-2026';
const JWT_SECRET = process.env.JWT_SECRET || DEFAULT_JWT_SECRET;
const JWT_EXPIRES = '24h';

// P0-2 安全加固：JWT_SECRET 仍是默认值 → 直接拒绝启动
if (JWT_SECRET === DEFAULT_JWT_SECRET) {
  console.error('\x1b[31m[FATAL] 安全性检查失败：JWT_SECRET 仍为默认硬编码值！\x1b[0m');
  console.error('\x1b[31m  请在启动前设置环境变量：\x1b[0m');
  console.error('\x1b[33m    export JWT_SECRET="$(openssl rand -base64 48)"\x1b[0m');
  console.error('\x1b[31m  服务器拒绝启动以避免默认密钥风险。\x1b[0m');
  process.exit(1);
}

console.log('\x1b[32m[OK] JWT_SECRET 校验通过（已使用自定义密钥）\x1b[0m');

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
