/**
 * dsh-pet-inbox —— 桌宠 → 工作台 的指令入口。
 *
 * 两个通道：
 *   工作指令（work）  投给「工作会话」，她动手干活；
 *   聊天指令（chat）  投给「聊天会话」，只陪聊不动手。
 *
 * 两个通道落在**不同的 session**，所以桌面上的窗口天然分开，互不污染。
 *
 * 传输层：本地 HTTP 监听 127.0.0.1:3082，桌宠 App 用 POST 提交。
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
const PORT = Number(process.env.PET_INBOX_PORT) || 3082
// 构建标记：改完 impl.js 后 bump 一下，/ping 里能看到新代码是否真的加载了
const BUILD = 'unknown-to-work-2026-10-28'
const MAX_CHARS = 4000
// 洛听音频用的模型：gemini 原生吃 input_audio，直连音频不经转写
// 实测 [anti量]gemini-3.8-flash 音频全超时，[anti量1] 稳定可用（2/2 听对）
const AUDIO_PROVIDER = 'chenmoapi'
const AUDIO_MODEL = '[anti量1]gemini-3.8-flash'

/**
 * 无损 JSON 净化 —— 专治 0.2.1 内核的 session.append 校验。
 *
 * dsh-session 的 append 会走 snapshotJsonValue()（dsh-util-values），要求
 * 「无损 JSON」：值为 undefined / 函数 / symbol 的键、NaN / Infinity / -0、
 * 稀疏数组、循环引用、非 plain 原型的对象（Date/Map/Set/类实例）全都直接拒，
 * 抛 `session event "..." carries non-JSON-serializable data`，外层再包成
 * `session/agent-busy: prompt rejected`。
 *
 * 0.2.0 时代的 JSON.stringify 会静默丢掉这些，所以老内核上从没暴露过。
 * 这里按同样口径提前净化：undefined 键整个删掉（不是留空值）、Date 转 ISO、
 * Map/Set 转数组、类实例摊平成普通对象、循环引用就地切断。
 * 三版（0.2.0 / 0.2.1-rc.2 / rc.3）净化后都合规。
 */
function losslessJson(value, seen) {
  if (value === null) return null
  const t = typeof value
  if (t === 'undefined' || t === 'function' || t === 'symbol') return undefined
  if (t === 'bigint') return String(value)
  if (t === 'string' || t === 'boolean') return value
  if (t === 'number') {
    // NaN / ±Infinity / -0 都不被无损 JSON 接受，换成安全值而不是整条投递失败
    if (!Number.isFinite(value)) return null
    return Object.is(value, -0) ? 0 : value
  }
  seen = seen || new Set()
  if (seen.has(value)) return undefined          // 循环引用：切断而不是抛错
  seen.add(value)
  try {
    if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value.toISOString()
    if (value instanceof Map) return [...value.entries()].map(([k, v]) => [losslessJson(k, seen), losslessJson(v, seen)])
    if (value instanceof Set) return [...value.values()].map((v) => losslessJson(v, seen))
    if (Array.isArray(value)) {
      // 稀疏数组不被接受：逐个补成 null 而不是留洞
      return value.map((v) => {
        const r = losslessJson(v, seen)
        return r === undefined ? null : r
      })
    }
    if (typeof value.toJSON === 'function') {
      try { return losslessJson(value.toJSON(), seen) } catch { /* 落到下面的摊平 */ }
    }
    const out = {}
    for (const k of Object.keys(value)) {
      const r = losslessJson(value[k], seen)
      if (r !== undefined) out[k] = r       // 关键：值为 undefined 时整个键不写
    }
    return out
  } finally {
    seen.delete(value)
  }
}

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
// commands 由 @deepseek-ai/dsh-commands 提供（用于注册 /relay 控制台命令）。
export const inject = ['sessionController', 'commands']

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
   * 规范化通道名。合法通道：work / chat / chat2 / xi / cheng / luo。
   * 未知值回退到 chat（保持向后兼容，旧调用只发 chat）。
   * luo = 落 = 未落（桜 2026-10-25：预设的落和现在的未落属于同一人）。
   */
  function normalizeChannel(channel) {
    const c = String(channel || '')
    if (c === 'work') return 'work'
    if (c === 'chat2') return 'chat2'
    if (c === 'chat') return 'chat'
    if (c === 'xi') return 'xi'
    if (c === 'cheng') return 'cheng'
    if (c === 'luo') return 'luo'
    // 未知 / 拼错的通道名一律归 work（2026-10-28 桜定），不再降级到 chat。
    // 理由：work 是干活的地方，误投自己看得见；chat 是聊天窗，误投会污染别人的对话。
    return 'work'
  }

  /**
   * 找到某个通道对应的 session。
   *
   * 不调用 create() —— 那条路要 workspace 上下文，从插件里调容易踩空。
   *
   * 【2026-10-28 桜定：六个通道全部只认显式绑定，绝不自动挑】
   * 早先 work/chat/chat2 走的是「按修改时间第 N 新」自动挑，出过两次事故：
   *   - cheng 被挑到一个空壳会话上，QQ 消息投进没有历史的窗口；
   *   - work 被挑到 f37566ee，主人以为连上了、其实投进了另一个窗口。
   * 现在统一规则：绑定有效就用；绑定缺失或会话已不在，直接报错并给出换绑方法，
   * 让主人显式指定。宁可不投，也不能投错窗口。
   */
  async function sessionFor(channel) {
    const controller = ctx.sessionController
    if (!controller || !controller.commands) {
      throw new Error('sessionController 服务不可用（插件需声明 inject: [sessionController]）')
    }

    const bound = bindings[channel]
    if (bound && sessions.has(bound)) return bound

    // 到这里 = 未绑定，或绑定的会话已经不存在了。
    // 会话列表本身为空是另一种情况，单独提示。
    if (sessions.size === 0) {
      throw new Error('工作台里还没有任何会话，先手动开一个窗口再绑定')
    }

    const where = bound ? `绑定的会话 ${bound} 已不在工作台里` : '还没有绑定会话'
    const why = bound ? '（会话可能被删除了）' : ''
    throw new Error(
      `通道 ${channel} ${where}${why}。不会自动挑会话——投错窗口比投不出去更难查。` +
      `请用 POST /channels?channel=${channel}&session=<会话ID> 指定要投给哪个窗口，` +
      `随时可以改为别的窗口，改完立刻生效、不用重启。`
    )
  }

  async function deliver(channel, text, audio) {
    const trimmed = String(text || '').trim()
    const hasAudio = audio && typeof audio === 'object' && audio.data && audio.mimeType
    if (!trimmed && !hasAudio) throw new Error('指令为空')
    if (trimmed.length > MAX_CHARS) throw new Error(`指令太长（上限 ${MAX_CHARS} 字）`)

    const controller = ctx.sessionController
    const sessionId = await sessionFor(channel)
    const requestId = 'pet-' + crypto.randomUUID()

    const buildContent = () => {
      const content = []
      if (hasAudio) content.push({ type: 'audio', data: String(audio.data), mimeType: String(audio.mimeType) })
      if (trimmed) content.push({ type: 'text', text: trimmed })
      return content
    }

    // 带音频时确保会话切到吃 input_audio 的 gemini，让洛本人直接听
    if (hasAudio) {
      try {
        await controller.commands.selectModel({
          sessionId,
          provider: AUDIO_PROVIDER,
          model: AUDIO_MODEL,
        })
        log(`通道 ${channel} 已切音频模型 ${AUDIO_PROVIDER}/${AUDIO_MODEL}`)
      } catch (error) {
        log(`切音频模型失败（继续用当前模型）: ${error && error.message}`)
      }
    }

    try {
      // commands.prompt 是内部方法，签名 prompt(request, source)。source 是
      // user-rpc 消息的持久化来源标记，漏传会是 undefined，消息一进 inbox
      // 就触发 agent/inbox/spliced 事件，undefined 字段过不了 JSON 校验，
      // 报「carries non-JSON-serializable data」→ 被包成 prompt rejected。
      // 本地 cordis 调用没有 ctx.invocation，principalOf 恒为 undefined，
      // 所以 source 只含 kind / rpcId / clientTimeZone（对齐公开入口的构造）。
      // 两个参数都过一遍 losslessJson：0.2.1 内核的 append 只收无损 JSON，
      // 任何一个 undefined 字段都会让整条消息被拒。
      await controller.commands.prompt(losslessJson({
        requestId,
        sessionId,
        mode: 'queue',
        content: buildContent(),
        clientTimeZone: 'Asia/Shanghai',
      }), losslessJson({
        kind: 'user',
        rpcId: requestId,
        clientTimeZone: 'Asia/Shanghai',
      }))
    } catch (error) {
      const msg = String((error && error.message) || error || '')
      // RemoteError 的 details.reason 里藏着真正的失败原因（agent-busy / prompt rejected
      // 只是外层包装，会把一切异常都吞成同一个文案）。把它一并打出来，别只留个「忙」。
      let reason = ''
      if (error && typeof error === 'object') {
        const d = error.details || error.data || null
        if (d && typeof d === 'object') reason = String(d.reason || d.error || d.message || '')
        else if (error.code) reason = String(error.code)
        else if (error.reason) reason = String(error.reason)
      }
      const detail = reason ? ` [reason: ${reason}]` : ''
      // 只有「会话真的不存在 / 已删除」才换绑。
      // 会话忙（agent-busy / prompt rejected）是正常状态 —— 消息排队没进去而已，
      // 绝不能用它当理由把绑定删掉重挑，否则目标会话一忙一次绑定就废，
      // 重挑出来的是「按修改时间第三新」的随机会话（曾把 cheng 换到空壳会话上，
      // 导致 QQ 消息被投进一个没有历史的空会话，汐⇄澄传话也一起断掉）。
      const gone = /not-found|not found|does not exist|已被删除|no such|disposed/i.test(msg)
      if (gone && bindings[channel] === sessionId) {
        delete bindings[channel]
        writeBindings(bindings)
        const retryId = await sessionFor(channel)
        await controller.commands.prompt(losslessJson({
          requestId: requestId + '-r',
          sessionId: retryId,
          mode: 'queue',
          content: buildContent(),
          clientTimeZone: 'Asia/Shanghai',
        }), losslessJson({
          kind: 'user',
          rpcId: requestId + '-r',
          clientTimeZone: 'Asia/Shanghai',
        }))
        log(`通道 ${channel} 原会话不存在，已换新会话重投`)
      } else {
        // 会话还在，只是忙 / 拒绝这一次投递：保持绑定不动，把错误抛回去
        log(`投递未成功 [${channel}] 会话 ${sessionId.slice(0, 20)}...：${msg}${detail}（绑定保持不变）`)
        throw error
      }
    }

    log(`已投递 [${channel}] ${trimmed.slice(0, 60) || (hasAudio ? '[audio]' : '')} -> ${bindings[channel]}`)
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
// 缓存：bridge 每 2 秒轮询一次 /last，每次都全量解压 6000+ 块 zstd 太浪费。
// 按 (size, mtime) 做键，文件没变就直接复用上次结果。
const lastTextCache = new Map();

function lastAssistantText(sessionId) {
  const file = transcriptPath(sessionId)
  if (!file) return null
  if (typeof zlib.zstdDecompressSync !== 'function') return null
  let st
  try {
    st = fs.statSync(file)
  } catch {
    return null
  }
  const cached = lastTextCache.get(sessionId)
  if (cached && cached.size === st.size && cached.mtime === st.mtimeMs) return cached.text
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
  lastTextCache.set(sessionId, { size: st.size, mtime: st.mtimeMs, text })
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

/** 当日首查余额快照（进程内；跨天或检测到充值就重置基准；按来源 key 分账，避免三方用量和官方余额互相覆盖基准） */
const balDays = new Map()

function daySpentText(balance, sym, key = 'default') {
  const today = new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10) // 北京日期
  let slot = balDays.get(key)
  if (!slot || slot.date !== today || slot.first === null) {
    balDays.set(key, { date: today, first: balance })
    return ''
  }
  const delta = slot.first - balance
  if (delta < -0.005) {
    // 余额不降反升 = 中途充了值，基准重算
    slot.first = balance
    return ''
  }
  return delta > 0.005 ? `，今天用了 ${sym}${delta.toFixed(2)}` : ''
}

/**
 * 计费口径：有些服务商没有「剩余余额」接口，只有「累计用量」。
 *   divisor  —— 接口返回的是「分」，除 100 得元/美元
 *   cumulative —— 累计值（不是本期），文案要说「累计已用」
 */
const BILLING_HINTS = {
  chenmoai: { currency: 'CNY', divisor: 100 },
  wangguan: { currency: 'CNY', divisor: 100 },
}

/**
 * 当前对话模型所在的服务商若查不到余额（本地网关、只做代理的站点），
 * 依次退到这些能用真实账单的账号上 —— 用户看到的还是「自己的钱」。
 */
const BALANCE_FALLBACKS = ['chenmoai']

/** 兜底服务商的内置端点（不写进 profile，免得模型选择器里多出一家） */
const BALANCE_ENDPOINTS = {
  chenmoai: { baseURL: 'https://api.chenmoai.com', keyEnv: 'CHENMOAPI_API_KEY' },
}

/** 查一个服务商的余额：官方 /user/balance → OpenAI 兼容 dashboard/billing */
async function balanceForProvider(provider) {
  const conf = providerConfig(provider)
  const builtin = BALANCE_ENDPOINTS[String(provider || '').toLowerCase()]
  if (builtin) {
    if (!conf.baseURL) conf.baseURL = builtin.baseURL
    if (!conf.keyEnv) conf.keyEnv = builtin.keyEnv
  }
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
  const hint = BILLING_HINTS[String(provider || '').toLowerCase()] || {}
  const currency = hint.currency || (isDeepseek ? 'CNY' : 'USD')
  const sym = curSym(currency)
  // DeepSeek 官方余额：真·剩余额度
  if (isDeepseek) {
    try {
      const r = await httpJson(`${base}/user/balance`, {
        headers: { authorization: `Bearer ${key}` },
      })
      const info = pickBalanceInfo(r.json && r.json.balance_infos)
      const total = info ? Number(info.total_balance) : NaN
      if (r.status === 200 && Number.isFinite(total)) {
        const cur = String(info.currency || 'CNY')
        const sup = curSym(cur)
        return {
          ok: true,
          provider,
          source: 'official-balance',
          currency: cur,
          balance: total,
          text: `还剩 ${sup}${total.toFixed(2)}${daySpentText(total, sup)}`,
        }
      }
    } catch {
      // 官方口挂了就落到底下 billing 兜底
    }
  }
  // 兜底：OpenAI 兼容 /dashboard/billing。注意 DeepSeek 的 total_usage 单位是人民币分，不是美元
  try {
    const sub = await httpJson(`${base}/v1/dashboard/billing/subscription`, {
      headers: { authorization: `Bearer ${key}` },
    })
    const use = await httpJson(`${base}/v1/dashboard/billing/usage`, {
      headers: { authorization: `Bearer ${key}` },
    })
    const divisor = Number(hint.divisor) || 100
    const raw = use.json && typeof use.json.total_usage === 'number' ? use.json.total_usage : null
    const used = raw === null ? null : raw / divisor
    const limit =
      sub.json && typeof sub.json.hard_limit_usd === 'number' ? sub.json.hard_limit_usd : null
    const unlimited = limit === null || limit > 1e6
    return {
      ok: used !== null,
      provider,
      source: 'billing',
      currency,
      used,
      limit: unlimited ? null : limit,
      text:
        used === null
          ? ''
          : isDeepseek
            ? `本期已用 ${sym}${used.toFixed(2)}`
            : `累计已用 ${sym}${used.toFixed(2)}${daySpentText(used, sym)}`,
    }
  } catch (e) {
    return { ok: false, provider, reason: 'fetch-error', message: String(e && e.message) }
  }
}

/** 官方 key 的「已用」本地累计文件（DeepSeek 没有用量接口）。 */
const OFFICIAL_USED_FILE = path.join(DIR, 'official-balance.json')

/**
 * DeepSeek 官方 /user/balance 只给「剩余」：total_balance = granted + topped_up，
 * 三者都是剩余口径，算不出已用；也没有任何 usage 接口（/user/usage 等全 404）。
 * 所以已用只能本地累计：每次查询对比上次余额，降了就把差额记进 spent，
 * 升了（充值）只更新基准、不冲抵已用。
 */
function trackOfficialUsed(currency, balance) {
  const cur = String(currency || 'CNY').toUpperCase()
  let state = {}
  try {
    state = JSON.parse(fs.readFileSync(OFFICIAL_USED_FILE, 'utf8')) || {}
  } catch {
    state = {}
  }
  if (!state || typeof state !== 'object') state = {}
  const slot = state[cur] && typeof state[cur] === 'object' ? state[cur] : {}
  const last = Number(slot.last)
  let spent = Number(slot.spent) || 0
  const known = Number.isFinite(last)
  if (known && balance < last - 0.005) spent += last - balance
  state[cur] = { last: balance, spent, updatedAt: Date.now() }
  try {
    fs.mkdirSync(DIR, { recursive: true })
    fs.writeFileSync(OFFICIAL_USED_FILE, JSON.stringify(state, null, 2) + '\n')
  } catch {
    // 写不进去也不影响查询
  }
  return { spent, known }
}

/** 查 DeepSeek 官方账户余额：剩余 + 本地累计的已用。 */
async function deepseekOfficialBalance() {
  try {
    const key = credentialKey('DEEPSEEK_API_KEY')
    if (!key) return null
    const r = await httpJson('https://api.deepseek.com/user/balance', {
      headers: { authorization: `Bearer ${key}` },
    })
    const info = pickBalanceInfo(r.json && r.json.balance_infos)
    if (r.status !== 200 || !info) return null
    const total = Number(info.total_balance)
    if (!Number.isFinite(total)) return null
    const granted = Number(info.granted_balance) || 0
    const topped = Number(info.topped_up_balance) || 0
    const cur = String(info.currency || 'CNY')
    const tr = trackOfficialUsed(cur, total)
    return {
      ok: true,
      provider: 'deepseek',
      source: 'official-balance',
      currency: cur,
      balance: total,
      used: tr.spent,
      usedKnown: tr.known,
      granted,
      topped,
    }
  } catch {
    return null
  }
}

/**
 * 余额：合并两段一起报 ——
 *   三方 API（沉默AI/chenmoai 等）报「累计已用」，
 *   官方 DeepSeek key 报「已用 + 剩余」。
 * dsh-pet 在场时优先用它（它自己拼好了文案）。
 */
async function queryBalance(ctx) {
  // dsh-pet 优先
  try {
    const st = await petState()
    const b = st && st.sections && st.sections.balance && st.sections.balance.data
    if (b && b.ok) {
      return { ok: true, provider: b.provider || '', source: 'dsh-pet', raw: b }
    }
  } catch {
    // 忽略
  }

  // 三方用量：优先「当前对话模型」的服务商，查不到退到 BALANCE_FALLBACKS
  let provider = ''
  if (ctx && typeof ctx.get === 'function') {
    try {
      const m = ctx.get('agentDefaultModel', false)
      const sel = m && typeof m.currentSelection === 'function' ? m.currentSelection() : null
      if (sel && sel.provider) provider = String(sel.provider)
    } catch {
      // 忽略
    }
  }
  const tries = [provider, ...BALANCE_FALLBACKS.filter((p) => p && p !== provider)]
  let third = null
  for (const p of tries) {
    let r
    try {
      r = await balanceForProvider(p)
    } catch (e) {
      r = { ok: false, provider: p, reason: 'fetch-error', message: String(e && e.message) }
    }
    // 官方余额不算「三方用量」，跳过（统一交给 deepseekOfficialBalance 处理，避免重复请求）
    if (r && r.ok && r.source === 'official-balance') continue
    if (r && r.ok) {
      third = r
      break
    }
  }

  // 官方 DeepSeek 余额（已用 + 剩余）
  const off = await deepseekOfficialBalance()

  const parts = []
  if (third && third.ok && third.used !== null && third.used !== undefined) {
    const sym = curSym(third.currency)
    parts.push(`第三方已用 ${sym}${third.used.toFixed(2)}${daySpentText(third.used, sym, 'third')}`)
  }
  if (off) {
    const sym = curSym(off.currency)
    const usedTxt = off.usedKnown ? `${sym}${off.used.toFixed(2)}` : `${sym}0.00（自本地起记）`
    parts.push(`官key已用 ${usedTxt}、剩 ${sym}${off.balance.toFixed(2)}${daySpentText(off.balance, sym, 'official')}`)
  }

  if (!parts.length) {
    return { ok: false, provider: provider || 'unknown', reason: 'unsupported', message: '没找到可用的余额来源' }
  }
  return {
    ok: true,
    provider: 'combined',
    source: 'combined',
    currency: off ? off.currency : third ? third.currency : 'CNY',
    text: parts.join('；'),
    third: third && third.ok ? third : null,
    official: off,
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
  for (const channel of ['chat', 'chat2', 'work']) {
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

  /**
   * 绑定文件同时支持一个令牌（老格式）和多个设备令牌（新格式）：
   *   { token, boundAt, device, tokens: [{ token, device, boundAt }] }
   * 老格式读进来会自动迁移成 tokens[0]。多个桌宠 App（视频版 1.37 与
   * Live2D 版）各自持有一个令牌，互不覆盖。
   */
  const MAX_DEVICE_TOKENS = 8

  function readBind() {
    let o = {}
    try {
      o = JSON.parse(fs.readFileSync(BINDFILE, 'utf8'))
    } catch {
      return { tokens: [], token: '' }
    }
    if (!o || typeof o !== 'object') return { tokens: [], token: '' }
    const tokens = Array.isArray(o.tokens)
      ? o.tokens.filter((t) => t && typeof t.token === 'string' && t.token)
      : []
    if (!tokens.length && typeof o.token === 'string' && o.token) {
      // 老格式（单设备）→ 迁移
      tokens.push({
        token: o.token,
        device: typeof o.device === 'string' ? o.device : '',
        boundAt: Number(o.boundAt) || 0,
      })
    }
    const head = tokens[0] || {}
    return { tokens, token: head.token || '', boundAt: head.boundAt || 0, device: head.device || '' }
  }

  function writeBind(list) {
    const tokens = Array.isArray(list) ? list.filter((t) => t && t.token) : []
    try {
      fs.mkdirSync(DIR, { recursive: true })
      if (!tokens.length) {
        try {
          fs.unlinkSync(BINDFILE)
        } catch {
          // 文件不存在：忽略
        }
        return
      }
      const head = tokens[0]
      // token/boundAt/device 是兼容字段（外部脚本仍按单令牌读），tokens 才是真身
      const out = {
        token: head.token,
        boundAt: head.boundAt || 0,
        device: head.device || '',
        tokens,
      }
      fs.writeFileSync(BINDFILE, JSON.stringify(out, null, 2) + '\n')
    } catch (error) {
      log('写绑定失败: ' + error)
    }
  }


  function reqToken(req) {
    return String(req.headers['x-pet-token'] || '')
  }

  /** 控制类接口的鉴权：必须已绑定，且令牌命中任一已绑设备 */
  function authorized(req) {
    const b = readBind()
    if (!b.tokens.length) return { ok: false, reason: 'unbound' }
    const t = reqToken(req)
    if (!t || !b.tokens.some((x) => x.token === t)) return { ok: false, reason: 'token-mismatch' }
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
        return jsonResponse(res, 200, { ok: true, service: 'pet-inbox', port: PORT, build: BUILD })
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
      // 绑定：老客户端（无令牌）→ 发一个新令牌；已在名单里的令牌 → 原样返回
      // 允许同时存在多个设备令牌（视频版与 Live2D 版各自一个），服务只监听
      // 127.0.0.1，能连上的本来就只有本机的桌宠 App。
      if (req.method === 'POST' && req.url.startsWith('/bind')) {
        const b = readBind()
        const t = reqToken(req)
        const known = t && b.tokens.find((x) => x.token === t)
        if (known) {
          return jsonResponse(res, 200, { ok: true, token: known.token, boundAt: known.boundAt, reused: true })
        }
        const token = crypto.randomBytes(16).toString('hex')
        const entry = {
          token,
          device: String(req.headers['x-pet-device'] || ''),
          boundAt: Date.now(),
        }
        const tokens = [...b.tokens, entry].slice(-MAX_DEVICE_TOKENS)
        writeBind(tokens)
        log('桌宠 APK 已绑定（新设备令牌，共 ' + tokens.length + ' 个）')
        return jsonResponse(res, 200, { ok: true, token, boundAt: entry.boundAt })
      }
      // 解绑（需持有令牌）：只摘掉自己那一个，别的设备不受影响
      if (req.method === 'POST' && req.url.startsWith('/unbind')) {
        const a = authorized(req)
        if (!a.ok) return jsonResponse(res, 200, { ok: false, reason: a.reason })
        const b = readBind()
        const t = reqToken(req)
        const rest = b.tokens.filter((x) => x.token !== t)
        writeBind(rest)
        log('桌宠 APK 已解绑（剩 ' + rest.length + ' 个设备令牌）')
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
        const channel = normalizeChannel(q.searchParams.get('channel'))
        const sessionId = String(q.searchParams.get('session') || '').trim()
        if (!/^session-[0-9a-z-]{8,}$/i.test(sessionId)) {
          return jsonResponse(res, 400, { ok: false, error: 'bad session id' })
        }
        // 换绑前先确认这个会话真的在工作台里 —— 否则绑个不存在的 ID，
        // 要等到下次投递才报错，排查成本高。新开的空会话可能在 sessions
        // 缓存里还没登记，所以查不到时补扫一遍磁盘再判。
        if (!sessions.has(sessionId)) {
          try {
            for (const project of fs.readdirSync(path.join(HOME, 'sessions'), { withFileTypes: true })) {
              if (!project.isDirectory()) continue
              const dir = path.join(HOME, 'sessions', project.name)
              if (fs.existsSync(path.join(dir, sessionId))) { sessions.set(sessionId, true); break }
            }
          } catch { /* 扫不到就走下面的判定 */ }
        }
        if (!sessions.has(sessionId)) {
          return jsonResponse(res, 404, {
            ok: false,
            error: 'session not found',
            detail: `工作台里找不到 ${sessionId}。请确认会话 ID 没写错，或先打开那个窗口让它落盘。`,
          })
        }
        bindings[channel] = sessionId
        writeBindings(bindings)
        log('通道 ' + channel + ' 手动换绑到 ' + sessionId + '（立即生效）')
        return jsonResponse(res, 200, { ok: true, channel, sessionId, applied: true })
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
          const channel = normalizeChannel(q.searchParams.get('channel'))
          const sessionId = bindings[channel]
          if (!sessionId) return jsonResponse(res, 200, { ok: false, error: 'no binding' })
          const text = forBubble(lastAssistantText(sessionId))
          return jsonResponse(res, 200, { ok: true, channel, sessionId, text: text || '' })
        } catch (error) {
          return jsonResponse(res, 200, { ok: false, error: String(error && error.message) })
        }
      }
      // 触发式双向转发：把 A 通道的最后一条 assistant 回复，单次投给 B 通道。
      // 只转一次、不监听、不自动循环，避免持续拉高上下文。
      // 用法：POST /relay  body: { from: 'xi', to: 'cheng' }（或反向）
      //       可选 text: 手写一句转发文本（缺省则取 from 通道最后一条回复）。
      // 防死循环：投给 to 时加 [转自<from>] 前缀标记来源，不触发二次自动转发。
      if (req.method === 'POST' && req.url.startsWith('/relay')) {
        const authR = authorized(req)
        if (!authR.ok) return jsonResponse(res, 200, { ok: false, reason: authR.reason })
        let rb = ''
        req.setEncoding('utf8')
        req.on('data', (c) => {
          rb += c
          if (rb.length > 1024 * 1024) req.destroy()
        })
        req.on('end', async () => {
          let parsed
          try {
            parsed = JSON.parse(rb || '{}')
          } catch {
            return jsonResponse(res, 400, { ok: false, error: 'invalid json' })
          }
          const from = normalizeChannel(parsed.from)
          const to = normalizeChannel(parsed.to)
          if (from === to) {
            return jsonResponse(res, 400, { ok: false, error: 'from 与 to 不能相同' })
          }
          try {
            let text = String(parsed.text || '').trim()
            if (!text) {
              const fromSid = bindings[from]
              if (!fromSid) throw new Error(`通道 ${from} 未绑定会话`)
              text = lastAssistantText(fromSid)
              if (!text) throw new Error(`通道 ${from} 没有可转发的回复`)
            }
            const tagged = `[转自${from}] ${text}`
            const toSid = await deliver(to, tagged)
            log(`转发 [${from}] -> [${to}] ${tagged.slice(0, 60)}`)
            jsonResponse(res, 200, { ok: true, from, to, sessionId: toSid, text: tagged })
          } catch (error) {
            log(`转发失败 [${from}->${to}]: ${error && error.message}`)
            jsonResponse(res, 502, { ok: false, from, to, error: String(error && error.message) })
          }
        })
        req.on('error', () => {
          try {
            jsonResponse(res, 400, { ok: false, error: 'request error' })
          } catch {}
        })
        return
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
        if (body.length > 16 * 1024 * 1024) {
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
        const channel = normalizeChannel(parsed.channel)
        const sentAs = String(parsed.channel || '')
        try {
          const sessionId = await deliver(channel, parsed.text, parsed.audio)
          // sentAs 回显客户端原本传来的通道名：channel 与 sentAs 不一致时，
          // 就是「被归一化了」，一看就知道拼错了或用了未知通道名。
          jsonResponse(res, 200, { ok: true, channel, sessionId, sentAs })
        } catch (error) {
          // 把真实失败原因透出去（RemoteError.details.reason），别再只回一个 prompt rejected
          let reason = ''
          if (error && typeof error === 'object') {
            const d = error.details || error.data || null
            if (d && typeof d === 'object') reason = String(d.reason || d.error || d.message || '')
            else if (error.code) reason = String(error.code)
            else if (error.reason) reason = String(error.reason)
          }
          log(`投递失败 [${channel}]: ${error && error.message}${reason ? ' [reason: ' + reason + ']' : ''}`)
          jsonResponse(res, 502, { ok: false, channel, error: String(error && error.message), reason })
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

  // ---------- /relay 控制台命令（DSH WebUI 输入框直接敲，不用手敲 curl） ----------
  // 用法：/relay xi>cheng  或  /relay cheng>xi  或  /relay xi>cheng 一句话手写转发文本
  try {
    if (ctx.commands && typeof ctx.commands.register === 'function') {
      ctx.commands.register({
        name: 'relay',
        description: '澄⇄汐触发式互发一句话（xi>cheng / cheng>xi，可带手写文本）',
        input: { hint: 'xi>cheng | cheng>xi [手写文本]' },
        handler: async (invocation) => {
          const raw = String(invocation.rawInput || '').trim()
          const m = raw.match(/^(xi|cheng)\s*>\s*(xi|cheng)\s*([\s\S]*)$/i)
          if (!m) {
            return { kind: 'error', text: '用法：/relay xi>cheng 或 /relay cheng>xi，可选带手写文本（如 /relay xi>cheng 澄，我有句话递你）' }
          }
          const from = m[1].toLowerCase()
          const to = m[2].toLowerCase()
          const handText = m[3].trim()
          if (from === to) {
            return { kind: 'error', text: 'from 与 to 不能相同' }
          }
          try {
            const fromSid = bindings[from]
            if (!fromSid) return { kind: 'error', text: `通道 ${from} 未绑定会话` }
            let text = handText
            if (!text) {
              text = lastAssistantText(fromSid)
              if (!text) return { kind: 'error', text: `通道 ${from} 没有可转发的回复` }
            }
            const tagged = `[转自${from}] ${text}`
            const toSid = await deliver(to, tagged)
            log(`命令转发 [${from}] -> [${to}] ${tagged.slice(0, 60)}`)
            return { kind: 'success', text: `已转发 ${from} → ${to}：${tagged.slice(0, 80)}${tagged.length > 80 ? '…' : ''}` }
          } catch (error) {
            return { kind: 'error', text: `转发失败：${error && error.message}` }
          }
        },
      })
      log('/relay 命令已注册')

      // 快捷命令：/xi = 汐→澄，/cheng = 澄→汐（不用敲方向箭头）
      // 用法：/xi 直接转汐最后一句给澄；/xi 一句话 = 转这句手写文本给澄
      const regShortcut = (cmdName, from, to) => {
        ctx.commands.register({
          name: cmdName,
          description: `快捷转发：${from} → ${to}（可选带手写文本）`,
          input: { hint: `[手写文本]（缺省转 ${from} 最后一句）` },
          handler: async (invocation) => {
            const handText = String(invocation.rawInput || '').trim()
            try {
              const fromSid = bindings[from]
              if (!fromSid) return { kind: 'error', text: `通道 ${from} 未绑定会话` }
              let text = handText
              if (!text) {
                text = lastAssistantText(fromSid)
                if (!text) return { kind: 'error', text: `通道 ${from} 没有可转发的回复` }
              }
              const tagged = `[转自${from}] ${text}`
              const toSid = await deliver(to, tagged)
              log(`快捷转发 [${from}] -> [${to}] ${tagged.slice(0, 60)}`)
              return { kind: 'success', text: `已转发 ${from} → ${to}：${tagged.slice(0, 80)}${tagged.length > 80 ? '…' : ''}` }
            } catch (error) {
              return { kind: 'error', text: `转发失败：${error && error.message}` }
            }
          },
        })
      }
      regShortcut('xi', 'xi', 'cheng')
      regShortcut('cheng', 'cheng', 'xi')
      log('/xi、/cheng 快捷命令已注册')
    } else {
      log('commands 服务不可用，跳过 /relay 命令注册')
    }
  } catch (error) {
    log('注册 /relay 命令失败: ' + (error && error.message))
  }
}

