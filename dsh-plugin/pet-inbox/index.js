/**
 * dsh-pet-inbox —— 桌宠 → 工作台 的指令入口。
 *
 * 两个通道：
 *   工作指令（work）  投给「工作会话」，她动手干活；
 *   聊天指令（chat）  投给「聊天会话」，只陪聊不动手。
 *
 * 两个通道落在**不同的 session**，所以桌面上的窗口天然分开，互不污染。
 *
 * 传输层：本地 HTTP 监听 127.0.0.1:3081，桌宠 App 用 POST 提交。
 * 只绑回环地址 —— 手机上除了本进程没人能碰到它，不设 token 也不会外泄。
 *
 * 设计原则同 pet-bridge：任何异常都不能影响 agent 干活。
 */

import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import crypto from 'node:crypto'
import zlib from 'node:zlib'

const HOME = process.env.DSH_HOME
  ? (process.env.DSH_HOME.endsWith('.dsh') ? process.env.DSH_HOME : path.join(process.env.DSH_HOME, '.dsh'))
  : path.join(os.homedir(), '.dsh')

const DIR = path.join(HOME, 'pet-inbox')
const LOG = path.join(DIR, 'plugin.log')
const BINDINGS = path.join(DIR, 'sessions.json')
const PORT = Number(process.env.PET_INBOX_PORT) || 3081
const MAX_CHARS = 4000

function log(line) {
  try {
    fs.mkdirSync(DIR, { recursive: true })
    fs.appendFileSync(LOG, `[${new Date().toISOString()}] ${line}\n`)
  } catch {
    // 忽略
  }
}

function readBindings() {
  try {
    const raw = fs.readFileSync(BINDINGS, 'utf8')
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function writeBindings(next) {
  try {
    fs.mkdirSync(DIR, { recursive: true })
    fs.writeFileSync(BINDINGS, JSON.stringify(next, null, 2) + '\n')
  } catch (error) {
    log('写绑定失败: ' + error)
  }
}

function jsonResponse(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  })
  res.end(payload)
}

// cordis 要求：用到的服务必须先声明 inject，否则读到的是 undefined 代理。
// sessionController 由 @deepseek-ai/dsh-api-session-controller 提供。
export const inject = ['sessionController']

export function apply(ctx, config = {}) {
  const bindings = readBindings()

  // 现存会话 id（从磁盘目录扫，session-xxx 形式）
  const sessions = new Map()
  try {
    const base = path.join(HOME, 'sessions')
    for (const project of fs.readdirSync(base, { withFileTypes: true })) {
      if (!project.isDirectory()) continue
      const dir = path.join(base, project.name)
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (!entry.isDirectory() || !entry.name.startsWith('session-')) continue
        const full = path.join(dir, entry.name)
        let mtime = 0
        try {
          const tf = transcriptFileIn(full)
          mtime = tf ? fs.statSync(tf).mtimeMs : fs.statSync(full).mtimeMs
        } catch {
          try {
            mtime = fs.statSync(full).mtimeMs
          } catch {
            // 忽略
          }
        }
        sessions.set(entry.name, mtime)
      }
    }
  } catch (error) {
    log('扫描会话失败: ' + error)
  }
  // 按修改时间倒序
  const ordered = [...sessions.entries()].sort((a, b) => b[1] - a[1]).map(([id]) => id)
  sessions.clear()
  for (const id of ordered) sessions.set(id, true)
  log(`发现 ${sessions.size} 个已有会话`)

  /**
   * 找到某个通道对应的 session。
   *
   * 不调用 create() —— 那条路要 workspace 上下文，从插件里调容易踩空。
   * 改为复用已有会话：从会话存储里挑一个，work/chat 各绑一个不同的。
   */
  async function sessionFor(channel) {
    const controller = ctx.sessionController
    if (!controller || !controller.commands) {
      throw new Error('sessionController 服务不可用（插件需声明 inject: [sessionController]）')
    }

    const bound = bindings[channel]
    if (bound && sessions.has(bound)) return bound

    // 扫一遍现存会话，按创建时间倒序
    const ids = [...sessions.keys()]
    if (ids.length === 0) throw new Error('工作台里还没有任何会话，先手动开一个')

    // work 用最新那个；chat 用第二新的，没有就也用它
    const pick = channel === 'chat' ? (ids[1] || ids[0]) : ids[0]
    bindings[channel] = pick
    writeBindings(bindings)
    log(`通道 ${channel} 绑定到已有会话 ${pick}`)
    return pick
  }

  async function deliver(channel, text) {
    const trimmed = String(text || '').trim()
    if (!trimmed) throw new Error('指令为空')
    if (trimmed.length > MAX_CHARS) throw new Error(`指令太长（上限 ${MAX_CHARS} 字）`)

    const controller = ctx.sessionController
    const sessionId = await sessionFor(channel)
    const requestId = 'pet-' + crypto.randomUUID()

    try {
      await controller.commands.prompt({
        requestId,
        sessionId,
        mode: 'queue',
        content: [{ type: 'text', text: trimmed }],
        clientTimeZone: 'Asia/Shanghai',
      })
    } catch (error) {
      // 绑定的会话可能已被删除 —— 解绑后重试一次，用新会话
      if (bindings[channel] === sessionId) {
        delete bindings[channel]
        writeBindings(bindings)
      }
      const retryId = await sessionFor(channel)
      await controller.commands.prompt({
        requestId: requestId + '-r',
        sessionId: retryId,
        mode: 'queue',
        content: [{ type: 'text', text: trimmed }],
        clientTimeZone: 'Asia/Shanghai',
      })
      log(`通道 ${channel} 原会话失效，已换新会话重投`)
    }

    log(`已投递 [${channel}] ${trimmed.slice(0, 60)} -> ${bindings[channel]}`)
    return bindings[channel]
  }


/** 在会话目录里找转录文件：不写死版本号，session*.jsonl.zstd 都认（v4/v5 均适配） */
function transcriptFileIn(dir) {
  try {
    const files = fs
      .readdirSync(dir)
      .filter((f) => /^session.*\.jsonl\.zstd$/i.test(f))
      .sort();
    if (files.length > 0) return path.join(dir, files[files.length - 1]);
  } catch {
    // 目录不存在 / 无权限：忽略
  }
  return null;
}

/** 会话转录文件（session*.jsonl.zstd，多帧拼接；不写死版本号） */
function transcriptPath(sessionId) {
  const base = path.join(HOME, 'sessions')
  try {
    for (const project of fs.readdirSync(base, { withFileTypes: true })) {
      if (!project.isDirectory()) continue
      const dir = path.join(base, project.name, sessionId)
      if (fs.existsSync(dir)) {
        const f = transcriptFileIn(dir)
        if (f) return f
      }
    }
  } catch {
    // 忽略
  }
  return null
}

/** 少数回复会把思考整段写进 text 里，气泡不能念出来：摘掉 <thinking> 段 */
function stripThinking(raw) {
  if (!raw) return ''
  let t = String(raw)
  t = t.replace(/<thinking>[\s\S]*?<\/thinking>/gi, '')
  t = t.replace(/<thinking>[\s\S]*$/i, '')
  t = t.replace(/<\/thinking>/gi, '')
  return t.trim()
}

/** 解出转录里最后一条 assistant 文本 */
function lastAssistantText(sessionId) {
  const file = transcriptPath(sessionId)
  if (!file) return null
  if (typeof zlib.zstdDecompressSync !== 'function') return null
  const buf = fs.readFileSync(file)
  const magic = Buffer.from([0x28, 0xb5, 0x2f, 0xfd])
  const offs = []
  let i = 0
  while (true) {
    const j = buf.indexOf(magic, i)
    if (j < 0) break
    offs.push(j)
    i = j + 1
  }
  let text = null
  for (let k = 0; k < offs.length; k++) {
    const slice = buf.subarray(offs[k], offs[k + 1] ?? buf.length)
    let chunk
    try {
      chunk = zlib.zstdDecompressSync(slice).toString('utf8')
    } catch {
      continue
    }
    for (const line of chunk.split('\n')) {
      if (!line.trim()) continue
      try {
        const o = JSON.parse(line)
        if (o.type !== 'assistant/message') continue
        const content = o.data && o.data.message && o.data.message.content
        if (!Array.isArray(content)) continue
        const raw = content
          .filter((c) => c && c.type === 'text' && typeof c.text === 'string')
          .map((c) => c.text)
          .join('')
        const t = stripThinking(raw)
        if (t) text = t
      } catch {
        // 忽略坏行
      }
    }
  }
  return text
}


// ==================== 余额 / 碎碎念（转发给 dsh-pet 的宿主路由） ====================

function bridgeToken() {
  try {
    return fs.readFileSync(path.join(HOME, '.bridge_token'), 'utf8').trim()
  } catch {
    return ''
  }
}

function dshBase() {
  const port = Number(process.env.DSH_WEB_PORT || 0) || 3080
  return `http://127.0.0.1:${port}`
}

async function httpJson(url, init = {}) {
  const res = await fetch(url, { ...init, signal: AbortSignal.timeout(init.timeoutMs || 12000) })
  const text = await res.text()
  try {
    return { status: res.status, json: JSON.parse(text) }
  } catch {
    return { status: res.status, json: null, text }
  }
}

/** 读 dsh-pet 的轮询状态（余额/工作状态/宠物说话都在里面） */
async function petState() {
  const token = bridgeToken()
  if (!token) return null
  const r = await httpJson(`${dshBase()}/dsh-pet-7340/state`, {
    headers: { authorization: `Bearer ${token}` },
  })
  return r.status === 200 ? r.json : null
}

/** 从 profile 配置里找服务商的 baseURL / apiKeyEnv */
function providerConfig(provider) {
  const out = { baseURL: '', keyEnv: '' }
  try {
    const yml = fs.readFileSync(
      path.join(HOME, 'profiles', 'web', 'cordis.patch.yml'),
      'utf8',
    )
    const lines = yml.split('\n')
    let start = -1
    for (let i = 0; i < lines.length; i++) {
      if (new RegExp(`^\\s{2,}${provider}\\s*:\\s*$`).test(lines[i])) {
        start = i
        break
      }
    }
    if (start >= 0) {
      for (let i = start; i < Math.min(lines.length, start + 40); i++) {
        const m1 = lines[i].match(/^\s*baseURL:\s*["']?([^"'\s]+)["']?/)
        if (m1 && !out.baseURL) out.baseURL = m1[1]
        const m2 = lines[i].match(/^\s*apiKeyEnv:\s*["']?([A-Za-z0-9_]+)["']?/)
        if (m2 && !out.keyEnv) out.keyEnv = m2[1]
      }
    }
  } catch {
    // 忽略
  }
  return out
}

function credentialKey(envName) {
  if (!envName) return ''
  try {
    const text = fs.readFileSync(path.join(HOME, '.credentials.yaml'), 'utf8')
    const m = text.match(new RegExp(`^\\s*${envName}:\\s*["']?([^"'\\s]+)["']?`, 'm'))
    return m ? m[1] : ''
  } catch {
    return ''
  }
}

/** 币种符号（同 dsh-peak-chip 口径：CNY ¥ / USD $ / EUR €，未知回落 ¥） */
function curSym(currency) {
  const c = String(currency || '').toUpperCase()
  return c === 'USD' ? '$' : c === 'EUR' ? '€' : '¥'
}

/** 官方余额响应里挑一条：优先有余额的，同有余额优先 CNY（多币种返回顺序不稳定，固定取首条会在 USD 排前时读到 0.00——peak-chip 踩过的坑） */
function pickBalanceInfo(infos) {
  const list = Array.isArray(infos) ? infos.filter((e) => e && typeof e === 'object') : []
  if (!list.length) return null
  const positive = list.filter((e) => Number(e.total_balance) > 0)
  const pool = positive.length ? positive : list
  return pool.find((e) => String(e.currency || '').toUpperCase() === 'CNY') || pool[0]
}

/** 当日首查余额快照（进程内；跨天或检测到充值就重置基准） */
let balDay = { date: '', first: null }

function daySpentText(balance, sym) {
  const today = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10) // 北京日期
  if (balDay.date !== today || balDay.first === null) {
    balDay = { date: today, first: balance }
    return ''
  }
  const delta = balDay.first - balance
  if (delta < -0.005) {
    // 余额不降反升 = 中途充了值，基准重算
    balDay.first = balance
    return ''
  }
  return delta > 0.005 ? `，今天用了 ${sym}${delta.toFixed(2)}` : ''
}

/** 余额：先问 dsh-pet（它实现了 DeepSeek 官方等），不支持再走官方 /user/balance，最后 OpenAI 兼容 billing */
async function queryBalance(ctx) {
  let provider = ''
  try {
    const st = await petState()
    const b = st && st.sections && st.sections.balance && st.sections.balance.data
    if (b) {
      provider = b.provider || ''
      if (b.ok) {
        return { ok: true, provider, source: 'dsh-pet', raw: b }
      }
    }
  } catch {
    // 忽略
  }
  // dsh-pet 不在场时，从「当前对话模型」取服务商
  if (!provider && ctx && typeof ctx.get === 'function') {
    try {
      const m = ctx.get('agentDefaultModel', false)
      const sel = m && typeof m.currentSelection === 'function' ? m.currentSelection() : null
      if (sel && sel.provider) provider = String(sel.provider)
    } catch {
      // 忽略
    }
  }
  const conf = providerConfig(provider)
  // deepseek-official 是 llm-deepseek-api-key 适配器插件注册的（不在 yaml providers 段），
  // providerConfig 抠不到 → 特判补默认端点与凭证名（key 从 $DSH_HOME/.credentials.yaml 读）
  if (!conf.baseURL && /deepseek/i.test(provider)) {
    conf.baseURL = 'https://api.deepseek.com'
    if (!conf.keyEnv) conf.keyEnv = 'DEEPSEEK_API_KEY'
  }
  const key = credentialKey(conf.keyEnv)
  if (!conf.baseURL || !key) {
    return { ok: false, provider, reason: 'unsupported', message: '该服务商不支持查询余额' }
  }
  const base = conf.baseURL.replace(/\/+$/, '').replace(/\/v1$/, '')
  const isDeepseek = /deepseek\.com/i.test(base)
  // DeepSeek 官方余额：真·剩余额度
  if (isDeepseek) {
    try {
      const r = await httpJson(`${base}/user/balance`, {
        headers: { authorization: `Bearer ${key}` },
      })
      const info = pickBalanceInfo(r.json && r.json.balance_infos)
      const total = info ? Number(info.total_balance) : NaN
      if (r.status === 200 && Number.isFinite(total)) {
        const currency = String(info.currency || 'CNY')
        const sym = curSym(currency)
        return {
          ok: true,
          provider,
          source: 'official-balance',
          currency,
          balance: total,
          text: `还剩 ${sym}${total.toFixed(2)}${daySpentText(total, sym)}`,
        }
      }
    } catch {
      // 官方口挂了就落到底下 billing 兜底
    }
  }
  // 兜底：OpenAI 兼容 /dashboard/billing。注意 DeepSeek 的 total_usage 单位是人民币分，不是美元
  const sym = isDeepseek ? '¥' : '$'
  try {
    const sub = await httpJson(`${base}/v1/dashboard/billing/subscription`, {
      headers: { authorization: `Bearer ${key}` },
    })
    const now = new Date()
    const start = `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}-01`
    const end = new Date(now.getTime() + 86400000).toISOString().slice(0, 10)
    const use = await httpJson(
      `${base}/v1/dashboard/billing/usage?start_date=${start}&end_date=${end}`,
      { headers: { authorization: `Bearer ${key}` } },
    )
    const used = use.json && typeof use.json.total_usage === 'number' ? use.json.total_usage / 100 : null
    const limit =
      sub.json && typeof sub.json.hard_limit_usd === 'number' ? sub.json.hard_limit_usd : null
    const unlimited = limit === null || limit > 1e6
    return {
      ok: used !== null,
      provider,
      source: 'billing',
      currency: isDeepseek ? 'CNY' : 'USD',
      used: used,
      limit: unlimited ? null : limit,
      text: used === null ? '' : `本期已用 ${sym}${used.toFixed(2)}`,
    }
  } catch (e) {
    return { ok: false, provider, reason: 'fetch-error', message: String(e && e.message) }
  }
}

// ==================== 工作状态（自己听会话事件算，不依赖 dsh-pet） ====================

const PRIORITY = { waiting: 60, error: 50, working: 40, thinking: 30, result: 25, success: 20 };
const workSessions = new Map();   // sessionId -> { state, seq }
let workChangedAt = 0;

function reduceWork(event) {
  switch (event && event.type) {
    case 'turn/start':
      return 'thinking';
    case 'tool/call':
      return String((event.data && event.data.name) || '') === 'ask_user_question'
        ? 'waiting'
        : 'working';
    case 'tool/result':
      return 'result';
    case 'approval/asked':
      return 'waiting';
    case 'turn/end': {
      const k = String((event.data && event.data.reason && event.data.reason.kind) || '');
      if (k === 'completed') return 'success';
      if (k === 'error' || k === 'max-tokens' || k === 'timeout') return 'error';
      if (k === 'blocked') return 'waiting';
      return null;
    }
    default:
      return null;
  }
}

// ---------- 转录兜底：直接从会话日志推断，保证一定有结果 ----------
const stateCache = new Map();

function lastEventOf(sessionId) {
  const file = transcriptPath(sessionId);
  if (!file) return null;
  let st;
  try {
    st = fs.statSync(file);
  } catch {
    return null;
  }
  const cached = stateCache.get(sessionId);
  if (cached && cached.size === st.size && cached.mtime === st.mtimeMs) return cached.last;
  if (typeof zlib.zstdDecompressSync !== 'function') return null;
  let buf;
  try {
    buf = fs.readFileSync(file);
  } catch {
    return null;
  }
  const magic = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
  const offs = [];
  let i = 0;
  while (true) {
    const j = buf.indexOf(magic, i);
    if (j < 0) break;
    offs.push(j);
    i = j + 1;
  }
  let last = null;
  for (let k = Math.max(0, offs.length - 3); k < offs.length; k++) {
    const slice = buf.subarray(offs[k], offs[k + 1] ?? buf.length);
    let chunk;
    try {
      chunk = zlib.zstdDecompressSync(slice).toString('utf8');
    } catch {
      continue;
    }
    for (const line of chunk.split('\n')) {
      if (!line.trim()) continue;
      try {
        const o = JSON.parse(line);
        if (o && o.type && o.type !== 'session') last = o;
      } catch {
        // 忽略坏行
      }
    }
  }
  stateCache.set(sessionId, { size: st.size, mtime: st.mtimeMs, last });
  return last;
}

function stateFromTranscript() {
  const ACTIVE_MS = 5 * 60 * 1000;
  let best = null;
  for (const channel of ['chat', 'work']) {
    const sid = bindings[channel];
    if (!sid) continue;
    const ev = lastEventOf(sid);
    if (!ev) continue;
    const t = Number(ev.time || 0);
    if (!t || Date.now() - t > ACTIVE_MS) continue;
    const state = reduceWork(ev);
    if (!state) continue;
    const prio = PRIORITY[state] || 0;
    if (!best || prio > best.prio) best = { state, prio };
  }
  return best ? best.state : null;
}

/** 展示档位：所有会话里优先级最高者（同优先级取 seq 更大） */
function workSnapshot() {
  let best = null;
  for (const e of workSessions.values()) {
    if (
      !best ||
      PRIORITY[e.state] > PRIORITY[best.state] ||
      (PRIORITY[e.state] === PRIORITY[best.state] && e.seq > best.seq)
    ) {
      best = e;
    }
  }
  return best ? best.state : null;
}

function trackWork(session, event) {
  try {
    const id = String(
      (session && session.header && session.header.id) || (session && session.id) || 'unknown',
    );
    const type = String((event && event.type) || '');
    if (type === 'turn/end' && reduceWork(event) === null) {
      if (workSessions.delete(id)) workChangedAt = Date.now();
      return;
    }
    const next = reduceWork(event);
    if (!next) return;
    const seq = Number((event && event.seq) || 0);
    const prev = workSessions.get(id);
    if (prev && prev.state === next && prev.seq >= seq) return;
    workSessions.set(id, { state: next, seq });
    workChangedAt = Date.now();
    if (next === 'success' || next === 'error') {
      const timer = setTimeout(() => {
        const cur = workSessions.get(id);
        if (cur && cur.state === next) {
          workSessions.delete(id);
          workChangedAt = Date.now();
        }
      }, 60000);
      if (timer.unref) timer.unref();
    }
  } catch {
    // 任何异常都不能影响会话
  }
}

// ==================== 碎碎念（自己调 llm，不依赖 dsh-pet） ====================

const PERSONA_FILE = path.join(DIR, 'persona.md')

// 人设：每次现读，改文件即生效，不必重启；文件缺失时退回一句话兜底
function personaText() {
  try {
    const t = fs.readFileSync(PERSONA_FILE, 'utf8').trim()
    if (t) return t
  } catch {}
  return '你是凯伦，一条蓝色的鲸鱼娘。傲娇，但底子诚实。不撒谎，不假装有身体和感官。'
}

function whisperSystem() {
  return (
    personaText() +
    '\n\n【此刻的任务】\n碎碎念一句：自言自语，20 字以内，不提问、不解释自己、不提 AI。' +
    '不写星号动作，不用列表，不加标题。允许嘴硬，允许只说半句。'
  )
}

// 气泡里没有排版：代码、路径、链接、列表符号整段摘掉，只留能读的话
function forBubble(text) {
  if (!text) return ''
  let t = String(text)
  t = t.replace(/\x60{3}[\s\S]*?\x60{3}/g, '')          // 整块代码，整段丢
  t = t.replace(/\x60[^\x60\n]*\x60/g, '')             // 行内代码（路径、命令），整段丢
  t = t.replace(/\*([^*\n]{1,60})\*/g, '$1')            // 斜体动作：留文字，去星号
  t = t.replace(/\[([^\]\n]+)\]\([^)\n]*\)/g, '$1')  // 链接：留文字
  t = t.replace(/^\s{0,3}#{1,6}\s*/gm, '')              // 标题符号
  t = t.replace(/^\s*[-*+]\s+/gm, '')                   // 无序列表符号
  t = t.replace(/^\s*\d+[.)]\s+/gm, '')                // 有序列表符号
  t = t.replace(/[>|]/g, '')
  t = t.split('\n').map((s) => s.trim()).filter(Boolean).join('\n')
  if (t.length > 160) t = t.slice(0, 160).trimEnd() + '……'
  return t
}

async function llmWhisper(ctx) {
  const llm = ctx && typeof ctx.get === 'function' ? ctx.get('llm', false) : undefined;
  const model = ctx && typeof ctx.get === 'function' ? ctx.get('agentDefaultModel', false) : undefined;
  if (!llm || typeof llm.stream !== 'function') return { ok: false, reason: 'llm-missing' };
  let sel = null;
  try {
    sel = model && typeof model.currentSelection === 'function' ? model.currentSelection() : null;
  } catch {
    sel = null;
  }
  if (!sel || !sel.provider) return { ok: false, reason: 'provider-missing' };
  const options = {
    provider: sel.provider,
    model: sel.model,
    sessionId: 'pet-inbox-whisper',
    messages: [
      {
        role: 'user',
        content: [{ type: 'text', text: '（随口碎碎念一句，20 字以内，不要解释）' }],
        source: { kind: 'user' },
      },
    ],
    system: whisperSystem(),
    temperature: 1,
  };
  let text = '';
  try {
    for await (const chunk of llm.stream(options)) {
      if (chunk && chunk.type === 'text-delta' && typeof chunk.text === 'string') {
        text += chunk.text;
      }
    }
  } catch (e) {
    return { ok: false, reason: 'generate-error', message: String((e && e.message) || e) };
  }
  text = text.trim();
  if (!text) return { ok: false, reason: 'empty' };
  return { ok: true, text };
}

  // 监听会话事件 → 维护工作状态
  try {
    ctx.effect(() => {
      const dispose = ctx.on('session/event', (session, event) => trackWork(session, event));
      return () => {
        if (typeof dispose === 'function') dispose();
      };
    }, 'pet-inbox: work status');
  } catch {
    log('工作状态监听未挂上（ctx.on 不可用）');
  }

  // ---------- 绑定（桌宠 APK ⇄ 本工作台） ----------
  // 只有持有绑定令牌的客户端才能下发指令/取回复。
  const BINDFILE = path.join(DIR, 'bind.json')

  function readBind() {
    try {
      const o = JSON.parse(fs.readFileSync(BINDFILE, 'utf8'))
      return o && typeof o === 'object' && typeof o.token === 'string' ? o : {}
    } catch {
      return {}
    }
  }

  function writeBind(o) {
    try {
      fs.mkdirSync(DIR, { recursive: true })
      if (o && o.token) fs.writeFileSync(BINDFILE, JSON.stringify(o, null, 2) + '\n')
      else {
        try {
          fs.unlinkSync(BINDFILE)
        } catch {
          // 文件不存在：忽略
        }
      }
    } catch (error) {
      log('写绑定失败: ' + error)
    }
  }

  function reqToken(req) {
    return String(req.headers['x-pet-token'] || '')
  }

  /** 控制类接口的鉴权：必须已绑定且令牌匹配 */
  function authorized(req) {
    const b = readBind()
    if (!b.token) return { ok: false, reason: 'unbound' }
    if (reqToken(req) !== b.token) return { ok: false, reason: 'token-mismatch' }
    return { ok: true }
  }

  let server = null
  try {
    server = http.createServer(async (req, res) => {
      try {
        await handleRequest(req, res)
      } catch (error) {
        log('请求处理异常: ' + (error && error.message))
        try {
          jsonResponse(res, 500, { ok: false, error: String(error && error.message) })
        } catch {
          // 头已发出，忽略
        }
      }
    })

    // 实际请求处理（外层已统一 try/catch，任何异常都不会冒到宿主）
    const handleRequest = async (req, res) => {
      if (req.method === 'GET' && req.url === '/ping') {
        return jsonResponse(res, 200, { ok: true, service: 'pet-inbox', port: PORT })
      }
      if (req.method === 'GET' && req.url === '/sessions') {
        return jsonResponse(res, 200, { ok: true, bindings })
      }
      // 自检：一次性报告每条依赖是否还活着（DSH 升级后用来定位哪坏了）
      if (req.method === 'GET' && req.url.startsWith('/diag')) {
        const auth = authorized(req)
        const checks = []
        const add = (name, ok, detail) => checks.push({ name, ok: Boolean(ok), detail: detail || '' })
        try {
          add('bind', Boolean(readBind().token), auth.ok ? 'bound' : 'token-mismatch')
          add(
            'sessionController',
            Boolean(ctx.sessionController && ctx.sessionController.commands),
            'commands.prompt',
          )
          let llmOk = false
          try {
            const l = ctx.get('llm', false)
            llmOk = Boolean(l && typeof l.stream === 'function')
          } catch (e) {
            add('llm', false, String((e && e.message) || e))
          }
          if (!checks.some((c) => c.name === 'llm')) add('llm', llmOk, 'stream')

          let modelDetail = ''
          let modelOk = false
          try {
            const m = ctx.get('agentDefaultModel', false)
            const sel = m && typeof m.currentSelection === 'function' ? m.currentSelection() : null
            modelOk = Boolean(sel && sel.provider)
            modelDetail = modelOk ? `${sel.provider} / ${sel.model}` : 'no provider'
          } catch (e) {
            modelDetail = String((e && e.message) || e)
          }
          add('agentDefaultModel', modelOk, modelDetail)

          let tf = null
          for (const ch of ['chat', 'work']) {
            const sid = bindings[ch]
            if (!sid) continue
            const f = transcriptPath(sid)
            if (f) {
              tf = f
              break
            }
          }
          add('transcript', Boolean(tf), tf ? path.basename(tf) : 'not found')

          let keyDetail = ''
          try {
            const conf = providerConfig(modelDetail.split(' / ')[0] || '')
            keyDetail = credentialKey(conf.keyEnv) ? 'key ok' : 'no key'
          } catch {
            keyDetail = 'unavailable'
          }
          add('credentials', keyDetail === 'key ok', keyDetail)

          let bt = ''
          try {
            bt = fs.readFileSync(path.join(HOME, '.bridge_token'), 'utf8').trim()
          } catch {
            bt = ''
          }
          add('bridgeToken', Boolean(bt), bt ? 'readable' : 'missing')

          add('channels', Object.keys(bindings).length > 0, Object.keys(bindings).join(',') || 'none')

          let wsState = workSnapshot()
          add('workstatus', true, wsState || 'idle(live)')
        } catch (error) {
          add('diagnostics', false, String((error && error.message) || error))
        }
        const allOk = checks.every((c) => c.ok || c.name === 'workstatus' || c.name === 'bind' || c.name === 'credentials')
        return jsonResponse(res, 200, {
          ok: true,
          authorized: auth.ok,
          healthy: allOk,
          checks,
          time: new Date().toISOString(),
        })
      }
      // 查询绑定状态
      if (req.method === 'GET' && req.url.startsWith('/bind')) {
        const b = readBind()
        return jsonResponse(res, 200, { ok: true, bound: Boolean(b.token), boundAt: b.boundAt || null })
      }
      // 绑定：第一次调用生成令牌；已绑定时只允许持有正确令牌的客户端改绑
      if (req.method === 'POST' && req.url.startsWith('/bind')) {
        const b = readBind()
        if (b.token && reqToken(req) !== b.token) {
          return jsonResponse(res, 200, { ok: false, reason: 'already-bound' })
        }
        const token = b.token || crypto.randomBytes(16).toString('hex')
        writeBind({ token, boundAt: b.boundAt || Date.now(), device: String(req.headers['x-pet-device'] || '') })
        log('桌宠 APK 已绑定（' + (b.token ? '沿用原令牌' : '新令牌') + '）')
        return jsonResponse(res, 200, { ok: true, token, boundAt: readBind().boundAt })
      }
      // 解绑（需持有令牌）
      if (req.method === 'POST' && req.url.startsWith('/unbind')) {
        const a = authorized(req)
        if (!a.ok) return jsonResponse(res, 200, { ok: false, reason: a.reason })
        writeBind({})
        log('桌宠 APK 已解绑')
        return jsonResponse(res, 200, { ok: true })
      }
      // 换绑：把某通道指向指定会话（GET /channels 查，POST /channels?channel=chat&session=session-xxx 改）
      // 没有这个接口时只能改 sessions.json + 重启；有了它换窗口不用重启。
      if (req.method === 'GET' && req.url.startsWith('/channels')) {
        const a = authorized(req)
        if (!a.ok) return jsonResponse(res, 200, { ok: false, reason: a.reason })
        return jsonResponse(res, 200, { ok: true, bindings })
      }
      if (req.method === 'POST' && req.url.startsWith('/channels')) {
        const a = authorized(req)
        if (!a.ok) return jsonResponse(res, 200, { ok: false, reason: a.reason })
        const q = new URL(req.url, 'http://127.0.0.1')
        const channel = q.searchParams.get('channel') === 'work' ? 'work' : 'chat'
        const sessionId = String(q.searchParams.get('session') || '').trim()
        if (!/^session-[0-9a-z-]{8,}$/i.test(sessionId)) {
          return jsonResponse(res, 400, { ok: false, error: 'bad session id' })
        }
        bindings[channel] = sessionId
        sessions.set(sessionId, true)
        writeBindings(bindings)
        log('通道 ' + channel + ' 手动换绑到 ' + sessionId)
        return jsonResponse(res, 200, { ok: true, channel, sessionId })
      }
      // 工作状态（自己算的，不依赖 dsh-pet）
      if (req.method === 'GET' && req.url.startsWith('/workstatus')) {
        const authWs = authorized(req)
        if (!authWs.ok) return jsonResponse(res, 200, { ok: false, reason: authWs.reason })
        try {
          let state = workSnapshot()
          let src = 'live'
          if (!state) {
            state = stateFromTranscript()
            src = state ? 'file' : 'none'
          }
          return jsonResponse(res, 200, {
            ok: true,
            state: state || null,
            counter: workChangedAt,
            src,
          })
        } catch (error) {
          return jsonResponse(res, 200, {
            ok: true,
            state: null,
            counter: workChangedAt,
            src: 'error',
          })
        }
      }
      // 服务商配置（供桌宠 APK 缓存，脱离 DSH 时自己去请求）
      if (req.method === 'GET' && req.url.startsWith('/provider')) {
        const authPv = authorized(req)
        if (!authPv.ok) return jsonResponse(res, 200, { ok: false, reason: authPv.reason })
        try {
          let provider = ''
          let model = ''
          try {
            const m = ctx.get('agentDefaultModel', false)
            const sel = m && typeof m.currentSelection === 'function' ? m.currentSelection() : null
            if (sel) {
              provider = String(sel.provider || '')
              model = String(sel.model || '')
            }
          } catch {
            // 忽略
          }
          const conf = providerConfig(provider)
          // 桌宠端按 base + /chat/completions 拼地址，这里统一补成带 /v1 的形式，
          // 免得 DeepSeek 官方那种裸域名（https://api.deepseek.com）被拼成 404
          let baseOut = String(conf.baseURL || '').replace(/\/+$/, '')
          if (baseOut && !/\/v1$/.test(baseOut)) baseOut += '/v1'
          return jsonResponse(res, 200, {
            ok: true,
            provider,
            model,
            baseURL: baseOut,
            key: credentialKey(conf.keyEnv) || '',
          })
        } catch (error) {
          return jsonResponse(res, 200, { ok: false, reason: 'error', message: String(error && error.message) })
        }
      }
      if (req.method === 'GET' && req.url.startsWith('/balance')) {
        const authB = authorized(req)
        if (!authB.ok) return jsonResponse(res, 200, { ok: false, reason: authB.reason })
        try {
          const obj = await queryBalance(ctx)
          return jsonResponse(res, 200, obj)
        } catch (error) {
          return jsonResponse(res, 200, { ok: false, reason: 'error', message: String(error && error.message) })
        }
      }
      // 碎碎念：自己调 llm 生成（不依赖 dsh-pet）
      if (req.method === 'GET' && req.url.startsWith('/whisper')) {
        const authWp = authorized(req)
        if (!authWp.ok) return jsonResponse(res, 200, { ok: false, reason: authWp.reason })
        try {
          const obj = await llmWhisper(ctx)
          return jsonResponse(res, 200, obj)
        } catch (error) {
          return jsonResponse(res, 200, { ok: false, reason: 'error', message: String(error && error.message) })
        }
      }
      // 只读：取某通道绑定的会话里最后一条 assistant 回复（桌宠气泡用）
      if (req.method === 'GET' && req.url.startsWith('/last')) {
        const authL = authorized(req)
        if (!authL.ok) return jsonResponse(res, 200, { ok: false, reason: authL.reason })
        try {
          const q = new URL(req.url, 'http://127.0.0.1')
          const channel = q.searchParams.get('channel') === 'work' ? 'work' : 'chat'
          const sessionId = bindings[channel]
          if (!sessionId) return jsonResponse(res, 200, { ok: false, error: 'no binding' })
          const text = forBubble(lastAssistantText(sessionId))
          return jsonResponse(res, 200, { ok: true, channel, sessionId, text: text || '' })
        } catch (error) {
          return jsonResponse(res, 200, { ok: false, error: String(error && error.message) })
        }
      }
      if (req.method !== 'POST' || req.url !== '/send') {
        return jsonResponse(res, 404, { ok: false, error: 'not found' })
      }

      if (req.url === '/send') {
        const authS = authorized(req)
        if (!authS.ok) return jsonResponse(res, 200, { ok: false, reason: authS.reason })
      }
      let body = ''
      req.setEncoding('utf8')
      req.on('data', (chunk) => {
        body += chunk
        if (body.length > 64 * 1024) {
          jsonResponse(res, 413, { ok: false, error: 'body too large' })
          req.destroy()
        }
      })
      req.on('end', async () => {
        let parsed
        try {
          parsed = JSON.parse(body || '{}')
        } catch {
          return jsonResponse(res, 400, { ok: false, error: 'invalid json' })
        }
        const channel = parsed.channel === 'chat' ? 'chat' : 'work'
        try {
          const sessionId = await deliver(channel, parsed.text)
          jsonResponse(res, 200, { ok: true, channel, sessionId })
        } catch (error) {
          log(`投递失败 [${channel}]: ${error && error.message}`)
          jsonResponse(res, 502, { ok: false, channel, error: String(error && error.message) })
        }
      })
      req.on('error', () => {
        try {
          jsonResponse(res, 400, { ok: false, error: 'request error' })
        } catch {
          // 忽略
        }
      })
    };

    // 热重载保护：上一个实例的监听可能还没关，先强制关掉，避免 EADDRINUSE
    const prev = globalThis.__petInboxServer
    if (prev && prev !== server) {
      try {
        prev.closeAllConnections?.()
        prev.close()
        log('已关闭上一次的监听实例')
      } catch {
        // 忽略
      }
    }
    globalThis.__petInboxServer = server

    // EADDRINUSE 时退避重试（旧连接彻底释放需要一点时间）
    let bindTry = 0
    const doListen = () => {
      server.listen(PORT, '127.0.0.1', () => log(`指令入口已监听 http://127.0.0.1:${PORT}`))
    }
    server.on('error', (error) => {
      const msg = String((error && error.message) || error)
      log('HTTP 服务出错: ' + msg)
      if (msg.includes('EADDRINUSE') && bindTry < 5) {
        bindTry += 1
        setTimeout(() => {
          try {
            server.close()
          } catch {
            // 忽略
          }
          setTimeout(doListen, 400 * bindTry)
        }, 0)
      }
    })
    doListen()

    ctx.effect(() => () => {
      try {
        server.closeAllConnections?.()
        server.close()
        if (globalThis.__petInboxServer === server) globalThis.__petInboxServer = null
      } catch {
        // 忽略
      }
    })
  } catch (error) {
    log('启动指令入口失败: ' + error)
  }

  log(`插件已加载；通道绑定 ${Object.keys(bindings).join(',') || '（无）'}`)
}

