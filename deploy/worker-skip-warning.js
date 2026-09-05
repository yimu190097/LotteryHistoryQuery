// ============================================================================
// Cloudflare Worker —— ngrok 拦截页跳过反代
//
// 作用：
//   把固定域名 https://showbiz-unbridle-decent.ngrok-free.dev
//   反代到一个固定不变的 <名称>.<子域>.workers.dev 地址，并在转发请求时
//   自动注入 ngrok-skip-browser-warning 请求头，彻底跳过 ngrok 免费版的
//   英文拦截页，用户访问新地址直接进入中文应用，不再看到英文警告页。
//
// 部署位置：Cloudflare 控制台 → Workers & Pages → 创建 Worker → 粘贴本代码
// 说明：免费计划每天 10 万次请求，足够日常使用；原 ngrok 域名仍可访问（但会弹英文页）。
// ============================================================================

// 目标：ngrok 固定域名（若域名变更，只需改这一行）
const TARGET = 'https://showbiz-unbridle-decent.ngrok-free.dev';

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const targetUrl = TARGET + url.pathname + url.search;

    // 判断是否为 WebSocket 升级请求（聊天/通话等场景需要直通）
    const isWS = (request.headers.get('Upgrade') || '').toLowerCase() === 'websocket';

    // 复制请求头，并注入跳过 ngrok 拦截页的请求头
    const headers = new Headers(request.headers);
    headers.set('ngrok-skip-browser-warning', '1');

    const init = {
      method: request.method,
      headers,
      redirect: 'manual',
    };
    // GET/HEAD 不带 body，其余方法透传请求体
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      init.body = request.body;
    }

    // 转发到 ngrok 固定域名
    const resp = await fetch(targetUrl, init);

    // WebSocket 升级请求直接返回原始响应（保持连接升级）
    if (isWS) {
      return resp;
    }

    // 普通 HTTP：透传状态码与响应体，去掉 ngrok 的错误码头避免干扰
    const outHeaders = new Headers(resp.headers);
    outHeaders.delete('ngrok-error-code');
    return new Response(resp.body, {
      status: resp.status,
      statusText: resp.statusText,
      headers: outHeaders,
    });
  },
};
