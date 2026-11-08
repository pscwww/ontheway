/*
 * 오는 길에 — 데모용 중계 서버
 *
 * Firebase Realtime Database의 REST + 스트리밍(SSE) 방식을 그대로 흉내 낸다.
 * 그래서 차 앱과 가족 웹페이지는 주소만 바꾸면 실제 Firebase에도 그대로 붙는다.
 *
 *   GET    /trips/demo.json                  값 읽기 (없으면 null)
 *   GET    /trips/demo.json  (Accept: text/event-stream)  실시간 구독
 *   PUT    /trips/demo/order.json            값 통째로 쓰기
 *   PATCH  /trips/demo.json                  일부 필드만 고치기 ("a/b": 값 형태도 됨)
 *   POST   /trips/demo/errands.json          새 항목 추가 → {"name": "<새 id>"}
 *   DELETE /trips/demo/errands/x.json        지우기
 *
 * 그 밖의 경로는 ../web 폴더의 파일을 보여준다 (가족 웹페이지).
 *
 * 실행: node relay/server.js   (Node 18 이상, 설치할 패키지 없음)
 * 차 앱(에뮬레이터)에서는 http://10.0.2.2:8787, 같은 와이파이의 폰에서는 http://<PC IP>:8787
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.env.PORT || 8787);
const WEB_DIR = path.join(__dirname, '..', 'web');
const DATA_FILE = path.join(__dirname, 'data.json');

let root = {};
try { root = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8')) || {}; } catch { root = {}; }

// ---------- 트리 다루기 ----------
const split = p => p.split('/').filter(Boolean);
function getAt(parts) {
  let n = root;
  for (const k of parts) { if (n === null || typeof n !== 'object' || !(k in n)) return null; n = n[k]; }
  return n === undefined ? null : n;
}
function clean(v) { // Firebase처럼 null과 빈 객체는 지운다
  if (v === null || typeof v !== 'object' || Array.isArray(v)) return v;
  const out = {};
  for (const [k, x] of Object.entries(v)) { const c = clean(x); if (c !== null && !(typeof c === 'object' && !Array.isArray(c) && Object.keys(c).length === 0)) out[k] = c; }
  return out;
}
function setAt(parts, value) {
  value = clean(value);
  if (parts.length === 0) { root = (value && typeof value === 'object') ? value : {}; return; }
  let n = root;
  for (const k of parts.slice(0, -1)) { if (typeof n[k] !== 'object' || n[k] === null) n[k] = {}; n = n[k]; }
  const last = parts[parts.length - 1];
  const empty = value === null || (typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0);
  if (empty) delete n[last]; else n[last] = value;
}
let saveTimer = null;
function save() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => fs.writeFile(DATA_FILE, JSON.stringify(root, null, 1), () => {}), 300);
}

// ---------- 실시간 구독 ----------
const subs = new Set(); // {parts, res}
function notify(changedParts) {
  for (const s of subs) {
    const sp = s.parts;
    const n = Math.min(sp.length, changedParts.length);
    let related = true;
    for (let i = 0; i < n; i++) if (sp[i] !== changedParts[i]) { related = false; break; }
    if (!related) continue;
    // 구독 경로 기준 상대 경로로 바뀐 부분만 보낸다 (Firebase와 같은 형식)
    if (changedParts.length >= sp.length) {
      const rel = changedParts.slice(sp.length);
      send(s.res, 'put', { path: '/' + rel.join('/'), data: getAt(changedParts) });
    } else {
      send(s.res, 'put', { path: '/', data: getAt(sp) });
    }
  }
}
function send(res, event, data) { res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`); }
setInterval(() => { for (const s of subs) s.res.write('event: keep-alive\ndata: null\n\n'); }, 25000);

// ---------- 정적 파일 ----------
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.mp3': 'audio/mpeg', '.woff': 'font/woff', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8' };
function serveStatic(req, res, urlPath) {
  let rel = decodeURIComponent(urlPath);
  if (rel === '/' || rel === '') rel = '/index.html';
  const file = path.normalize(path.join(WEB_DIR, rel));
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('없는 페이지예요'); }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(buf);
  });
}

// ---------- 서버 ----------
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET,PUT,PATCH,POST,DELETE,OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type, Accept, X-HTTP-Method-Override' };
function readBody(req) {
  return new Promise((ok, fail) => {
    let s = ''; req.setEncoding('utf8');
    req.on('data', c => { s += c; if (s.length > 1e6) req.destroy(); });
    req.on('end', () => { try { ok(s ? JSON.parse(s) : null); } catch (e) { fail(e); } });
  });
}
function pushId() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 8); }
function json(res, code, v) { res.writeHead(code, { ...CORS, 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(v)); }

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
  if (!url.pathname.endsWith('.json') || url.pathname.startsWith('/manifest')) return serveStatic(req, res, url.pathname);

  const parts = split(url.pathname.slice(0, -'.json'.length));
  // Firebase처럼 X-HTTP-Method-Override 지원 (안드로이드 HttpURLConnection은 PATCH를 못 보냄)
  const method = String(req.headers['x-http-method-override'] || req.method).toUpperCase();
  try {
    if (method === 'GET') {
      if ((req.headers.accept || '').includes('text/event-stream')) {
        res.writeHead(200, { ...CORS, 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        const sub = { parts, res };
        subs.add(sub);
        send(res, 'put', { path: '/', data: getAt(parts) });
        req.on('close', () => subs.delete(sub));
        return;
      }
      return json(res, 200, getAt(parts));
    }
    const body = await readBody(req);
    if (method === 'PUT') { setAt(parts, body); save(); notify(parts); return json(res, 200, body); }
    if (method === 'PATCH') {
      for (const [k, v] of Object.entries(body || {})) { const p = parts.concat(split(k)); setAt(p, v); notify(p); }
      save(); return json(res, 200, body);
    }
    if (method === 'POST') { const id = pushId(); const p = parts.concat(id); setAt(p, body); save(); notify(p); return json(res, 200, { name: id }); }
    if (method === 'DELETE') { setAt(parts, null); save(); notify(parts); return json(res, 200, null); }
    json(res, 405, { error: 'method' });
  } catch (e) { json(res, 400, { error: String(e.message || e) }); }
}).listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter(i => i && i.family === 'IPv4' && !i.internal).map(i => i.address);
  console.log(`오는 길에 중계 서버 실행 중 (포트 ${PORT})`);
  console.log(`  PC 브라우저:      http://localhost:${PORT}`);
  ips.forEach(ip => console.log(`  같은 와이파이 폰: http://${ip}:${PORT}`));
  console.log(`  에뮬레이터 차 앱: http://10.0.2.2:${PORT}`);
});
