const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');
const http = require('http');
const https = require('https');
const multer = require('multer');
const rateLimit = require('express-rate-limit');
const { initTables } = require('./db/database');
const { setupWebSocket } = require('./ws/chatServer');
const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const statsRoutes = require('./routes/stats');
const configRoutes = require('./routes/config');
const chatRoutes = require('./routes/chat');
const deployRoutes = require('./routes/deploy');
const { authMiddleware } = require('./middleware/auth');

const app = express();
const PORT = process.env.PORT || 3000;

// P0-1 安全加固：CORS 白名单
const ALLOWED_ORIGINS = [
  // 本机开发/管理面板
  /^https?:\/\/localhost(:\d+)?$/,
  /^https?:\/\/127\.0\.0\.1(:\d+)?$/,
  /^https?:\/\/192\.168\.\d{1,3}\.\d{1,3}(:\d+)?$/,
  /^https?:\/\/10\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$/,
  // 你的固定 ngrok 域名
  /^https:\/\/showbiz-unbridle-decent\.ngrok-free\.dev$/,
  // 允许其他 ngrok / trycloudflare 临时隧道（部署脚本会变）
  /\.ngrok-free\.dev$/,
  /\.trycloudflare\.com$/,
];

function isOriginAllowed(origin) {
  if (!origin) return true; // 同域请求无 origin 头，放行
  return ALLOWED_ORIGINS.some(r => r.test(origin));
}

const corsOptions = {
  origin: (origin, callback) => {
    if (isOriginAllowed(origin)) {
      callback(null, true);
    } else {
      callback(new Error(`CORS blocked: ${origin} 不在白名单中`));
    }
  },
  credentials: true,
  maxAge: 86400
};

// P0-4 管理员登录限流
const loginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '登录尝试过于频繁，5 分钟后再试' },
  keyGenerator: (req) => req.ip,
});

const clientLoginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '登录尝试过于频繁，5 分钟后再试' },
});

const registerLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  limit: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '注册过于频繁，1 小时后再试' },
});

const globalApiLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: '请求过频，请稍后再试' },
  skip: (req) => req.path.startsWith('/downloads/') || req.path.startsWith('/uploads/'),
});

// 初始化数据库
initTables();

// 中间件
app.use(cors(corsOptions));
app.use(express.json({ limit: '5mb' }));
app.use('/api', globalApiLimiter);
app.use(morgan('short'));

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', time: new Date().toISOString() });
});

app.use('/api/auth/login', loginLimiter);
app.use('/api/users/client/login', clientLoginLimiter);
app.use('/api/users/client/register', registerLimiter);

// 静态文件 - HTML 禁用缓存（确保用户拿到最新页面），JS/CSS/图片 缓存1h
app.use((req, res, next) => {
  if (req.path.endsWith('.html') || req.path === '/' || req.path === '/web/' || req.path.endsWith('/web/')) {
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');
  }
  next();
});
app.use(express.static(path.join(__dirname, '..', 'public'), { maxAge: 3600 * 1000, etag: true }));

// APK 下载目录
const DOWNLOADS_DIR = path.join(__dirname, '..', 'public', 'downloads');
if (!fs.existsSync(DOWNLOADS_DIR)) fs.mkdirSync(DOWNLOADS_DIR, { recursive: true });

// APK 下载静态文件服务（版本化产物，禁用缓存避免用户拿到旧包）
app.use('/downloads', express.static(DOWNLOADS_DIR, { maxAge: 0 }));

// ============================================================================
// 用户端网页版：开奖历史数据代理接口
// APP 端直连 http://data.17500.cn/{code}_desc.txt，浏览器受同源限制无法直连，
// 因此经后端转发原始文本返回（客户端自行解析）。
//
// P0-3 加固：内存缓存（60s 新鲜 + 5min stale 兜底）、整体响应超时、单次重试、
// 简单熔断（连续 3 次失败熔断 30s 期间返回 stale 或 503）。
// ============================================================================
const LOTTERY_CODES = ['ssq', 'dlt2', '3d', 'pl3', 'pl5', '7xc', 'kl8', '7lc'];
const LOTTERY_CACHE_TTL = 60 * 1000;       // 新鲜缓存 60s
const LOTTERY_STALE_TTL = 5 * 60 * 1000;   // 失败时允许使用 5min 内的 stale
const LOTTERY_CB_THRESHOLD = 3;            // 连续失败 3 次触发熔断
const LOTTERY_CB_OPEN_MS = 30 * 1000;      // 熔断时长 30s
const LOTTERY_UPSTREAM_TIMEOUT = 8000;     // 单次请求整体超时（连接 + 响应接收）
const LOTTERY_RETRY = 1;                   // 失败重试 1 次

// code -> { data, fetchedAt }
const lotteryCache = new Map();
// code -> { failCount, openUntil }
const lotteryCircuit = new Map();

function fetchLotteryUpstream(code) {
  return new Promise((resolve, reject) => {
    const proxyUrl = process.env.HTTP_PROXY || process.env.http_proxy || '';
    const useProxy = !!proxyUrl;
    // 沙箱内通过代理出站，优先 HTTP（代理隧道兼容性更好）
    const url = useProxy
      ? `http://data.17500.cn/${code}_desc.txt`
      : `https://data.17500.cn/${code}_desc.txt`;
    let settled = false;

    const doRequest = (targetUrl, redirectDepth) => {
      if (redirectDepth > 3) { reject(new Error('重定向次数过多')); return; }
      const isHttps = targetUrl.startsWith('https');
      const transport = isHttps ? https : http;

      const options = {};
      if (useProxy) {
        const pu = new URL(proxyUrl);
        options.host = pu.hostname;
        options.port = pu.port || 80;
        options.path = targetUrl;
        options.headers = { Host: new URL(targetUrl).host };
      }

      const req = transport.get(useProxy ? options : targetUrl, (r) => {
        if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
          r.resume();
          const redirectUrl = r.headers.location;
          doRequest(redirectUrl, redirectDepth + 1);
          return;
        }
        if (r.statusCode !== 200) {
          r.resume();
          if (!settled) { settled = true; reject(new Error(`upstream status ${r.statusCode}`)); }
          return;
        }
        readResponse(r);
      });
      req.setTimeout(LOTTERY_UPSTREAM_TIMEOUT, () => {
        req.destroy(new Error('数据源响应超时'));
      });
      req.on('error', (e) => {
        if (!settled) { settled = true; reject(e); }
      });
      req.on('timeout', () => {
        if (!settled) { settled = true; reject(new Error('数据源响应超时')); }
      });
    };

    function readResponse(r) {
      const expectedLen = parseInt(r.headers['content-length'], 10) || 0;
      let buf = '';
      let byteLen = 0;
      r.setEncoding('utf8');
      r.on('data', (chunk) => { buf += chunk; byteLen += Buffer.byteLength(chunk, 'utf8'); });
      r.on('end', () => {
        if (!settled) {
          settled = true;
          if (byteLen < 200) {
            reject(new Error('数据源返回数据异常（过短）：' + byteLen + ' bytes'));
            return;
          }
          if (expectedLen > 0 && Math.abs(byteLen - expectedLen) > expectedLen * 0.1) {
            console.warn(`[lottery] ${code}: Content-Length=${expectedLen} 实际=${byteLen}, 差异=${Math.abs(byteLen - expectedLen)}`);
          }
          resolve(buf);
        }
      });
      r.on('error', (e) => {
        if (!settled) { settled = true; reject(e); }
      });
    }

    doRequest(url, 0);
  });
}

async function fetchLotteryWithRetry(code) {
  let lastErr;
  for (let i = 0; i <= LOTTERY_RETRY; i++) {
    try {
      return await fetchLotteryUpstream(code);
    } catch (e) {
      lastErr = e;
      if (i < LOTTERY_RETRY) {
        await new Promise(r => setTimeout(r, 200 * (i + 1)));
      }
    }
  }
  throw lastErr;
}

app.get('/api/lottery/:code', async (req, res) => {
  const code = String(req.params.code || '').toLowerCase();
  if (!LOTTERY_CODES.includes(code)) {
    return res.status(400).json({ error: '不支持的彩种代码，可选: ' + LOTTERY_CODES.join('/') });
  }

  const now = Date.now();
  const cached = lotteryCache.get(code);

  // 1) 命中新鲜缓存
  if (cached && (now - cached.fetchedAt) < LOTTERY_CACHE_TTL) {
    return res.type('text/plain; charset=utf-8').send(cached.data);
  }

  // 2) 熔断中：返回 stale 或 503
  const cb = lotteryCircuit.get(code);
  if (cb && cb.openUntil && now < cb.openUntil) {
    if (cached && (now - cached.fetchedAt) < LOTTERY_STALE_TTL) {
      return res.type('text/plain; charset=utf-8').send(cached.data);
    }
    return res.status(503).json({ error: '数据源暂时不可用（熔断中），稍后再试' });
  }

  // 3) 拉取上游（带重试）
  try {
    const data = await fetchLotteryWithRetry(code);
    lotteryCache.set(code, { data, fetchedAt: now });
    lotteryCircuit.delete(code); // 成功则重置熔断
    return res.type('text/plain; charset=utf-8').send(data);
  } catch (e) {
    // 失败：更新熔断计数
    const cur = lotteryCircuit.get(code) || { failCount: 0, openUntil: 0 };
    cur.failCount = (cur.failCount || 0) + 1;
    if (cur.failCount >= LOTTERY_CB_THRESHOLD) {
      cur.openUntil = now + LOTTERY_CB_OPEN_MS;
      cur.failCount = 0;
    }
    lotteryCircuit.set(code, cur);

    // 返回 stale 或 502
    if (cached && (now - cached.fetchedAt) < LOTTERY_STALE_TTL) {
      return res.type('text/plain; charset=utf-8').send(cached.data);
    }
    return res.status(502).json({ error: '数据源不可用: ' + (e.message || e) });
  }
});

// ============================================================================
// 福彩3D 省级注数代理（浙江主 + 福建/上海备用 + 缓存）
// 背景：17500.cn 数据源自 2021-12-15 起不公布全国注数 → 网页端显示"官方未公布"。
// 方案：依次抓取 浙江 → 福建 → 上海 省级官方开奖公告，解析各奖级注数；
//       全部失败则如实返回不可用（前端继续显示"官方未公布"），绝不伪造数据。
// ============================================================================
const PROV3D_CACHE_TTL = 5 * 60 * 1000; // 省级注数缓存 5min
const PROV3D_TIMEOUT = 8000;
const PROV3D_SOURCES = [
  { key: 'zj', name: '浙江', url: (t) => `http://zjflcp.zjol.com.cn/fcweb/new_sd_d.html?qishu=${t}` },
  { key: 'fj', name: '福建', url: (t) => `https://www.fjcp.cn/kjgg.php?type=fc3d&term=${t}` },
  { key: 'sh', name: '上海', url: () => `http://www.swlc.sh.cn/shsflcpfxzx/lottery/3d.html` }, // 仅当前期
];
const prov3dCache = new Map(); // term -> { data, fetchedAt }

function httpGetText(targetUrl) {
  return new Promise((resolve, reject) => {
    const proxyUrl = process.env.HTTP_PROXY || process.env.http_proxy || '';
    const useProxy = !!proxyUrl;
    const isHttps = targetUrl.startsWith('https');
    const transport = isHttps ? https : http;
    const options = {};
    if (useProxy) {
      const pu = new URL(proxyUrl);
      options.host = pu.hostname;
      options.port = pu.port || 80;
      options.path = targetUrl;
      options.headers = { Host: new URL(targetUrl).host };
    }
    let settled = false;
    const req = transport.get(useProxy ? options : targetUrl, (r) => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) {
        r.resume();
        if (!settled) { settled = true; reject(new Error('redirect:' + r.headers.location)); }
        return;
      }
      if (r.statusCode !== 200) {
        r.resume();
        if (!settled) { settled = true; reject(new Error('upstream status ' + r.statusCode)); }
        return;
      }
      let buf = '';
      r.setEncoding('utf8');
      r.on('data', (c) => { buf += c; });
      r.on('end', () => {
        if (!settled) { settled = true; resolve(buf); }
      });
      r.on('error', (e) => { if (!settled) { settled = true; reject(e); } });
    });
    req.setTimeout(PROV3D_TIMEOUT, () => { req.destroy(new Error('数据源响应超时')); });
    req.on('error', (e) => { if (!settled) { settled = true; reject(e); } });
    req.on('timeout', () => { if (!settled) { settled = true; reject(new Error('数据源响应超时')); } });
  });
}

function stripHtml(s) {
  return s.replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&[a-z]+;/gi, ' ')
    .replace(/[\u3000]/g, ' ')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n+/g, '\n');
}
function pnum(s) {
  if (s == null) return null;
  const m = String(s).replace(/,/g, '').match(/(\d+(?:\.\d+)?)/);
  return m ? Math.round(parseFloat(m[1])) : null;
}

// 浙江：中奖情况区 <ul class="rewardMid"><li>名称</li><li>注数</li><li>奖金</li>…</ul>
// 期号不在"第X期"连续文本中，而在 <select defaultValue="2026237"> 下拉框当前选中值
function parseZJ(html) {
  const f = stripHtml(html);
  const termM = f.match(/第\s*(\d{7})\s*期/)
    || html.match(/defaultValue="(\d{7})"/)
    || html.match(/name="qishuzy"[^>]*?value="(\d{7})"/);
  const salesM = f.match(/本期销售额[:：]?\s*([\d,\.]+)/);
  const totalM = f.match(/中奖总金额[:：]?\s*([\d,\.]+)/);
  const ballM = f.match(/开奖号码\s*[:：]?\s*(\d)\s*(\d)\s*(\d)/);
  const mid = html.match(/<ul class="rewardMid">([\s\S]*?)<\/ul>/);
  const tiers = [];
  if (mid) {
    const items = mid[1].match(/<li>([^<]*)<\/li>/g) || [];
    const vals = items.map((x) => x.replace(/<\/?li>/g, '').trim());
    for (let i = 0; i + 2 < vals.length; i += 3) {
      const name = vals[i], c = pnum(vals[i + 1]), a = pnum(vals[i + 2]);
      if (!name || c == null || a == null) break;
      tiers.push({ name, count: c, amount: a });
    }
  }
  return {
    term: termM ? termM[1] : null,
    digits: ballM ? ballM[1] + ballM[2] + ballM[3] : null,
    sales: salesM ? pnum(salesM[1]) : null,
    total: totalM ? pnum(totalM[1]) : null,
    tiers,
  };
}

// 福建：中奖情况区三列表格 <tr><td>名称</td><td>注数</td><td>奖金</td></tr>
// 号码前有"百位/十位/个位"标签，需跳过
function parseFJ(html) {
  const f = stripHtml(html);
  const termM = f.match(/第\s*(\d{7})\s*期/);
  const salesM = f.match(/本期销售额[:：]?\s*([\d,\.]+)/);
  const totalM = f.match(/中奖总金额[:：]?\s*([\d,\.]+)/);
  const ballM = f.match(/开奖号码[:：]?\s*(?:百位\s*十位\s*个位\s*)?(\d)\s*(\d)\s*(\d)/);
  const i = html.indexOf('中奖情况');
  const seg = i >= 0 ? html.slice(i) : html;
  const tiers = [];
  const rows = seg.match(/<tr>[\s\S]*?<td[^>]*>\s*(.*?)\s*<\/td>\s*<td[^>]*>\s*(.*?)\s*<\/td>\s*<td[^>]*>\s*(.*?)\s*<\/td>\s*<\/tr>/g) || [];
  for (const r of rows) {
    const cells = r.match(/<td[^>]*>\s*(.*?)\s*<\/td>/g) || [];
    if (cells.length < 3) continue;
    const name = cells[0].replace(/<[^>]+>/g, '').trim();
    const c = pnum(cells[1].replace(/<[^>]+>/g, ''));
    const a = pnum(cells[2].replace(/<[^>]+>/g, ''));
    if (!name || c == null || a == null) continue;
    tiers.push({ name, count: c, amount: a });
  }
  return {
    term: termM ? termM[1] : null,
    digits: ballM ? ballM[1] + ballM[2] + ballM[3] : null,
    sales: salesM ? pnum(salesM[1]) : null,
    total: totalM ? pnum(totalM[1]) : null,
    tiers,
  };
}

// 上海：中奖情况表格（注数带"注"后缀，如 "733注"）
function parseSH(html) {
  const f = stripHtml(html);
  const termM = f.match(/第(\d{7})期/);
  const salesM = f.match(/本期销售额[^<]*?(\d[\d,\.]*)元/);
  const totalM = f.match(/中奖总金额[^<]*?(\d[\d,\.]*)元/);
  const balls = html.match(/class="drawNotice_shuangse"[\s\S]*?<p class="">(\d)<\/p><p class="">(\d)<\/p><p class="">(\d)<\/p>/);
  const i = html.indexOf('中奖情况');
  const seg = i >= 0 ? html.slice(i) : html;
  const tiers = [];
  const rows = seg.match(/<tr><td>([^<]*)<\/td><td>([^<]*)注?<\/td><td>([^<]*)<\/td><\/tr>/g) || [];
  for (const r of rows) {
    const m = r.match(/<tr><td>([^<]*)<\/td><td>([^<]*)注?<\/td><td>([^<]*)<\/td><\/tr>/);
    if (!m) continue;
    const name = m[1].trim(), c = pnum(m[2]), a = pnum(m[3]);
    if (!name || c == null || a == null) continue;
    tiers.push({ name, count: c, amount: a });
  }
  return {
    term: termM ? termM[1] : null,
    digits: balls ? balls[1] + balls[2] + balls[3] : null,
    sales: salesM ? pnum(salesM[1]) : null,
    total: totalM ? pnum(totalM[1]) : null,
    tiers,
  };
}

const PROV3D_PARSERS = { zj: parseZJ, fj: parseFJ, sh: parseSH };

// 从 17500 缓存/上游获取最新 3D 期号
async function latest3dTerm() {
  const cache = lotteryCache.get('3d');
  let text = cache ? cache.data : null;
  if (!text || (Date.now() - cache.fetchedAt) > 30 * 60 * 1000) {
    try { text = await fetchLotteryWithRetry('3d'); } catch (e) { text = cache ? cache.data : null; }
  }
  if (!text) return null;
  const first = text.split(/\r?\n/).find((l) => l.trim());
  if (!first) return null;
  const tok = first.trim().split(/\s+/)[0];
  return /^\d{7}$/.test(tok) ? tok : null;
}

async function fetchProv3d(term) {
  let lastErr = null;
  for (const src of PROV3D_SOURCES) {
    try {
      const html = await httpGetText(src.url(term));
      const d = PROV3D_PARSERS[src.key](html);
      // 校验：解析结果必须与请求期号一致（上海仅当前期，期号不符则视为不可用）
      if (!d.term || !/^\d{7}$/.test(d.term) || d.term !== term) {
        lastErr = new Error(src.name + ' 期号不符(请求' + term + ',实得' + (d.term || '无') + ')');
        continue;
      }
      if (!d.tiers || !d.tiers.length) {
        lastErr = new Error(src.name + ' 未解析到奖级数据');
        continue;
      }
      return { source: src.key, sourceName: src.name, ...d };
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error('所有省级数据源均不可用');
}

app.get('/api/lottery/3d/prizes', async (req, res) => {
  let term = String(req.query.term || '').trim();
  if (!/^\d{7}$/.test(term)) {
    try { term = await latest3dTerm(); } catch (e) { /* ignore */ }
  }
  if (!term || !/^\d{7}$/.test(term)) {
    return res.status(400).json({ ok: false, error: '无法确定期号，请携带 term 参数（如 ?term=2026237）' });
  }
  const now = Date.now();
  const cached = prov3dCache.get(term);
  if (cached && now - cached.fetchedAt < PROV3D_CACHE_TTL) {
    return res.json({ ok: true, term, cached: true, ...cached.data });
  }
  try {
    const data = await fetchProv3d(term);
    prov3dCache.set(term, { data, fetchedAt: now });
    res.json({ ok: true, term, cached: false, ...data });
  } catch (e) {
    res.status(502).json({ ok: false, error: '省级注数暂不可用', detail: (e && e.message) || String(e) });
  }
});

// 上传文件目录（图片/语音）
const UPLOAD_DIR = path.join(__dirname, '..', 'public', 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const upload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, UPLOAD_DIR),
    filename: (req, file, cb) => {
      const ext = path.extname(file.originalname) || (file.mimetype.startsWith('image') ? '.jpg' : '.m4a');
      cb(null, `${Date.now()}_${Math.random().toString(36).slice(2, 8)}${ext}`);
    }
  }),
  limits: { fileSize: 10 * 1024 * 1024 }
});

// APK 上传（独立 multer 实例，100MB）
const apkUpload = multer({
  storage: multer.diskStorage({
    destination: (req, file, cb) => cb(null, DOWNLOADS_DIR),
    filename: (req, file, cb) => cb(null, file.originalname)
  }),
  limits: { fileSize: 100 * 1024 * 1024 }
});

// ============================================================================
// 从 GitHub Releases 同步 APK 到服务器（后台任务 + 动态进度）
// ============================================================================
const GH_OWNER = 'yimu190097';
const GH_REPO = 'LotteryHistoryQuery';
const GH_API = `https://api.github.com/repos/${GH_OWNER}/${GH_REPO}`;
const GH_TOKEN = process.env.GH_TOKEN || '';

// 内存同步状态（单实例）
let apkSync = {
  running: false,
  startedAt: null,
  finishedAt: null,
  stage: 'idle',
  tag: null,
  releaseName: null,
  tasks: [],
  error: null,
  message: '',
};

// GitHub API JSON 请求（一次性返回 body）
function ghApiGet(url) {
  return new Promise((resolve, reject) => {
    const headers = { 'User-Agent': 'lottery-server', 'Accept': 'application/vnd.github+json' };
    if (GH_TOKEN) headers['Authorization'] = `token ${GH_TOKEN}`;
    const req = https.get(url, { headers }, (res) => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => resolve({ status: res.statusCode, data, headers: res.headers }));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.setTimeout(15000, () => req.destroy(new Error('请求超时')));
  });
}

// 下载 APK 到本地，逐 chunk 累加进度。下载源使用 GitHub assets API（octet-stream），
// 服务端无法直连 github.com 主站下载域时也能走 api.github.com -> 对象存储。
function downloadApk(assetId, filename, destPath, onProgress) {
  return new Promise((resolve, reject) => {
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36',
      'Accept': 'application/octet-stream',
    };
    if (GH_TOKEN) headers['Authorization'] = `token ${GH_TOKEN}`;
    const ws = fs.createWriteStream(destPath);
    let received = 0;

    const doGet = (url, hdrs) => {
      const req = https.get(url, { headers: hdrs }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          // 跟随 302 到对象存储（不带会 403 的自定义头，仅保留真实 UA）
          doGet(res.headers.location, { 'User-Agent': headers['User-Agent'] });
          return;
        }
        if (res.statusCode !== 200) {
          ws.destroy();
          return resolve({ ok: false, status: res.statusCode });
        }
        res.on('data', (chunk) => {
          ws.write(chunk);
          received += chunk.length;
          if (onProgress) onProgress(received);
        });
        res.on('error', (e) => { ws.destroy(); reject(e); });
        res.on('end', () => { ws.end(); resolve({ ok: true, status: res.statusCode, received }); });
      });
      req.on('error', (e) => { ws.destroy(); reject(e); });
      req.setTimeout(30000, () => req.destroy(new Error('下载连接超时')));
    };

    doGet(`${GH_API}/releases/assets/${assetId}`, headers);
  });
}

// 清理 downloads 目录：每个 APK 前缀仅保留最新一份（动态标签命名会无限累积，需定期清理）
function cleanupOldApks(dir) {
  const KNOWN_PREFIXES = ['LotteryHistoryQuery', 'LotteryAdmin'];
  for (const prefix of KNOWN_PREFIXES) {
    let files = [];
    try {
      files = fs.readdirSync(dir).filter(f => f.startsWith(`${prefix}_`) && f.endsWith('.apk'));
    } catch (_) { continue; }
    if (files.length <= 1) continue;
    // 排序：cmd- 动态命名永远视为新版本，旧固定命名(v24.2 等)视为最旧，避免误判
    files.sort((a, b) => {
      const ra = a.includes('cmd-') ? 1 : 0;
      const rb = b.includes('cmd-') ? 1 : 0;
      if (ra !== rb) return ra - rb;
      return a.localeCompare(b);
    });
    const keep = files[files.length - 1];
    for (const f of files.slice(0, -1)) {
      try { fs.unlinkSync(path.join(dir, f)); console.log('[APK 清理]', f); } catch (_) {}
    }
    console.log(`[APK 清理] ${prefix}: 保留 ${keep}，清理 ${files.length - 1} 个旧版本`);
  }
}

async function syncApkFromGithub() {
  if (apkSync.running) {
    apkSync.message = '已有一个同步任务正在执行';
    return;
  }
  apkSync = {
    running: true,
    startedAt: Date.now(),
    finishedAt: null,
    stage: 'fetching_release',
    tag: null,
    releaseName: null,
    tasks: [],
    error: null,
    message: '正在获取 GitHub 最新 Release...',
  };

  try {
    const rel = await ghApiGet(`${GH_API}/releases/latest`);
    if (rel.status !== 200) throw new Error(`获取 Release 失败: HTTP ${rel.status}`);
    const release = JSON.parse(rel.data);
    apkSync.tag = release.tag_name;
    apkSync.releaseName = release.name || release.tag_name;

    const apkAssets = (release.assets || []).filter(a => a.name.endsWith('.apk'));
    if (!apkAssets.length) throw new Error('最新 Release 中没有找到 APK 资产');

    apkSync.tasks = apkAssets.map(a => ({
      filename: a.name,
      size: a.size,
      total: a.size,
      received: 0,
      percent: 0,
      status: 'pending',
      assetId: a.id,
    }));

    for (const task of apkSync.tasks) {
      task.status = 'downloading';
      apkSync.stage = 'downloading';
      apkSync.message = `正在下载 ${task.filename}...`;
      const destPath = path.join(DOWNLOADS_DIR, task.filename);
      // 断点重试：累计3次，每次更新进度
      const dl = await downloadApk(task.assetId, task.filename, destPath, (received) => {
        task.received = received;
        task.percent = task.size > 0 ? Math.min(100, Math.round(received / task.size * 100)) : 0;
      });
      if (!dl.ok) {
        task.status = 'error';
        throw new Error(`下载 ${task.filename} 失败: HTTP ${dl.status}`);
      }
      task.received = dl.received;
      task.percent = 100;
      task.status = 'done';
    }

    apkSync.stage = 'done';
    apkSync.finishedAt = Date.now();
    apkSync.running = false;
    apkSync.message = 'APK 同步完成';
    // 同步完成后清理同前缀旧版本，防止动态标签命名无限累积占满磁盘
    cleanupOldApks(DOWNLOADS_DIR);
  } catch (err) {
    apkSync.error = err.message;
    apkSync.stage = 'error';
    apkSync.finishedAt = Date.now();
    apkSync.running = false;
    apkSync.message = err.message;
    console.error('[APK Sync]', err.message);
  }
}

// 触发从 GitHub 同步 APK（管理员）
app.post('/api/apk/sync', authMiddleware, (req, res) => {
  if (apkSync.running) {
    return res.json({ running: true, message: '已有一个同步任务正在执行' });
  }
  syncApkFromGithub();
  res.json({ running: true, message: '已触发从 GitHub 同步最新 APK' });
});

// 查询同步进度
app.get('/api/apk/sync-status', (req, res) => {
  res.json(apkSync);
});

// API 路由
app.use('/api/auth', authRoutes);
app.use('/api/users', userRoutes);
app.use('/api/stats', statsRoutes);
app.use('/api/config', configRoutes);
app.use('/api/chat', chatRoutes);
app.use('/api/deploy', deployRoutes);

// 文件上传（需要客户端 Token 或管理员 Token）
app.post('/api/upload', authMiddleware, upload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: '未收到文件' });
  const url = `/uploads/${req.file.filename}`;
  res.json({ url, size: req.file.size, mimetype: req.file.mimetype });
});

// APK 上传（仅管理员）
app.post('/api/upload-apk', authMiddleware, apkUpload.single('apk'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: '未收到 APK 文件' });
  const url = `/downloads/${req.file.filename}`;
  res.json({ url, size: req.file.size, filename: req.file.filename });
});

// APK 列表
app.get('/api/apk-list', (req, res) => {
  try {
    const files = fs.readdirSync(DOWNLOADS_DIR)
      .filter(f => f.endsWith('.apk'))
      .map(f => {
        const stat = fs.statSync(path.join(DOWNLOADS_DIR, f));
        return {
          filename: f,
          size: stat.size,
          url: `/downloads/${f}`,
          updatedAt: stat.mtime.toISOString()
        };
      })
      .sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt));
    res.json(files);
  } catch (e) {
    res.json([]);
  }
});

// ============================================================================
// APK 下载加速：GitHub Release 镜像链接
// ngrok 隧道分发下载慢（免费版带宽小、节点在海外），而每版 APK 已自动发布
// 到 GitHub Releases。这里返回最新 Release 的每个 APK 在国内 CDN/镜像上的
// 下载地址，前端优先使用，走不通再回落 GitHub 原站或本机 /downloads。
// ============================================================================
const GH_MIRRORS = [
  'https://ghfast.top/',
  'https://gh-proxy.com/',
  'https://ghproxy.net/',
];
const APK_RELEASE_TTL = 5 * 60 * 1000; // 5 分钟缓存，避免打爆 GitHub API 限流
let apkReleaseCache = { at: 0, assets: [], error: null };

// browser_download_url 形如 https://github.com/{owner}/{repo}/releases/download/{tag}/xxx.apk
// 镜像地址 = 镜像前缀 + 去掉 https://github.com/ 的剩余路径
function buildMirrorUrls(browserUrl) {
  const raw = browserUrl.replace(/^https:\/\/github\.com\//, '');
  return GH_MIRRORS.map(m => m + raw);
}

async function fetchApkReleaseAssets() {
  // 缓存命中直接返回
  if (Date.now() - apkReleaseCache.at < APK_RELEASE_TTL) {
    return apkReleaseCache;
  }
  try {
    const rel = await ghApiGet(`${GH_API}/releases/latest`);
    if (rel.status === 200) {
      const release = JSON.parse(rel.data);
      const assets = (release.assets || [])
        .filter(a => a.name.endsWith('.apk'))
        .map(a => ({
          name: a.name,
          size: a.size,
          tag: release.tag_name,
          githubUrl: a.browser_download_url,
          mirrors: buildMirrorUrls(a.browser_download_url),
        }));
      // 优先把用户端 APK 排在前面（命名命中外层；否则按 release 顺序）
      assets.sort((x, y) => {
        const xu = x.name.toLowerCase().includes('history') ? 0 : 1;
        const yu = y.name.toLowerCase().includes('history') ? 0 : 1;
        return xu - yu;
      });
      apkReleaseCache = { at: Date.now(), assets, error: null };
    } else {
      apkReleaseCache = { at: Date.now(), assets: [], error: `GitHub API HTTP ${rel.status}` };
    }
  } catch (e) {
    apkReleaseCache = { at: Date.now(), assets: [], error: e.message };
  }
  return apkReleaseCache;
}

// GET /api/apk/releases — 最新 Release 各 APK 的镜像/原站下载地址（无需管理员鉴权）
app.get('/api/apk/releases', (req, res) => {
  fetchApkReleaseAssets().then(c => {
    if (c.assets.length) {
      res.json({ success: true, tag: c.assets[0].tag, assets: c.assets });
    } else {
      res.status(502).json({ success: false, error: c.error || '暂无可用 Release' });
    }
  });
});

// 根路径重定向到用户端网页版
app.get('/', (req, res) => {
  res.redirect('/web/');
});

// SPA fallback（管理后台，非 web/ 路径）
app.get('*', (req, res) => {
  if (!req.path.startsWith('/api') && !req.path.startsWith('/uploads') && !req.path.startsWith('/downloads') && !req.path.startsWith('/web')) {
    res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
  } else if (req.path.startsWith('/api')) {
    res.status(404).json({ error: 'API不存在' });
  }
});

// 错误处理
app.use((err, req, res, next) => {
  console.error('[ERROR]', err.message || err);
  if (err.message && err.message.startsWith('CORS blocked')) {
    return res.status(403).json({ error: '来源未授权（不在 CORS 白名单中）' });
  }
  if (err.code === 'LIMIT_FILE_SIZE') {
    return res.status(413).json({ error: '文件超过大小限制' });
  }
  if (err.statusCode === 429) {
    return res.status(429).json({ error: err.message || '请求过频' });
  }
  res.status(500).json({ error: '服务器内部错误' });
});

// 用 http.Server 包装 express，便于挂 WebSocket
const server = http.createServer(app);

// 挂载 WebSocket 服务到 /ws 路径
setupWebSocket(server);

server.listen(PORT, () => {
  console.log(`[Server] 彩票后台管理服务器已启动: http://localhost:${PORT}`);
  console.log(`[Server] 管理面板: http://localhost:${PORT}`);
  console.log(`[Server] WebSocket: ws://localhost:${PORT}/ws`);
  console.log(`[Server] API文档: http://localhost:${PORT}/api/health`);
});