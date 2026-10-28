# 与 DeepSeek Harness 的接入说明

桌宠不认识 DSH 的内部结构，只认一个**本机 HTTP 小服务**（pet-inbox 插件，不开源、随 App 内置）。
这份文档把两端协议、关键代码、首次绑定和排障一次讲清。

---

## 1. 拓扑

```
┌──────────────────────┐                    ┌───────────────────────────┐
│  鲸鱼娘 App            │  POST /send        │  pet-inbox 插件（不开源）  │
│  com.dsh.petoverlay   │  x-pet-token       │  127.0.0.1:3081           │
│  PetService / Command │ ─────────────────▶ │  ~/.dsh/pet-inbox/bind.json│
└──────────────────────┘ ◀───────────────── └─────────────┬─────────────┘
        │                                                   │ ctx.sessionController
        │ 直连服务商（独立模式，DSH 不在时）                    │ .commands.prompt()
        ▼                                                   ▼
   OpenAI 兼容 API                                    DSH agent 会话
```

- **端口**：默认 `3081`；插件可用 `PET_INBOX_PORT` 覆盖，App 在设置卡片 4 里配同一端口。
- **只绑回环**：手机之外（含同一 Wi-Fi 的其他设备）连不上，这是设计，不是缺陷。
- **不需要 API Key 也能用**：只要 DSH 在跑，走的就是工作台自己的额度。

---

## 2. 跑起来

### 2.1 插件侧

插件不开源，随 App 内置（`assets/pet-inbox/`）。在鲸鱼娘设置页点「安装 / 更新插件到工作台」，
App 解包插件文件并生成安装命令，你在 DSH 里贴一下执行即可；启用后监听 `127.0.0.1:3081`。

环境变量（可选）：
- `PET_INBOX_PORT=3081`：覆盖监听端口；
- `DSH_WEB_PORT=3080`：插件反查工作台状态（余额/工作状态）用。

插件启动日志里会出现 `桌宠收件箱已监听 127.0.0.1:3081`。

### 2.2 App 侧

设置页 **卡片 4（指令入口）**：主机 `127.0.0.1`、端口 `3081`。
第一次打开 App 会自动 `POST /bind` 换令牌，之后每个请求都带 `x-pet-token`。

### 2.3 验证

```bash
curl -s http://127.0.0.1:3081/ping
# {"ok":true,"bound":true,...}
curl -s -X POST http://127.0.0.1:3081/send \
  -H "x-pet-token: $(node -e 'console.log(require("/root/.dsh/pet-inbox/bind.json").token)')" \
  -H "content-type: application/json" \
  -d '{"channel":"chat","text":"测试：你好"}'
# {"ok":true,"channel":"chat","sessionId":"session-..."}
```

---

## 3. 协议表

所有响应都是 JSON。除 `/ping`、`/bind`、`/diag` 外，均需请求头 `x-pet-token`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/ping` | 存活探测，返回 `{ok, bound, port}` |
| GET | `/diag` | 自检信息（绑定、会话、端口冲突线索） |
| POST | `/bind` | 首次调用生成 16 字节 hex 令牌；已绑定时带对令牌可沿用，否则 `{ok:false,reason:"already-bound"}` |
| GET | `/bind` | 查询绑定状态 |
| POST | `/unbind` | 解绑（需令牌） |
| POST | `/send` | `{channel:"chat"\|"work", text:"..."}` → 投递给对应会话，`mode:"queue"` |
| GET | `/channels` | 查两个通道当前绑定的会话；`POST /channels?channel=chat&session=session-xxx` 换绑 |
| GET | `/sessions` | 列出可绑定的会话 |
| GET | `/last?channel=chat` | 取该通道最后一条回复 |
| GET | `/provider` | 返回当前服务商的 `baseUrl / model / key`（App 缓存用于独立模式；base 已保证以 `/v1` 结尾） |
| GET | `/balance` | 余额（转发 DSH 的宿主路由） |
| GET | `/workstatus` | 工作状态轮询 |
| GET | `/whisper` | 碎碎念（定时自说自话） |

---

## 4. App 侧代码块

### 4.1 地址与鉴权

```java
private String inboxBase() {
  String host = pref("inbox_host", "127.0.0.1");
  String port = pref("inbox_port", "3081");
  if (host.isEmpty()) host = "127.0.0.1";
  if (port.isEmpty()) port = "3081";
  return "http://" + host + ":" + port;
}

private String petToken() {
  return getSharedPreferences("pet", MODE_PRIVATE).getString("token", "");
}

/** 所有请求都带绑定令牌 */
private void applyAuth(HttpURLConnection c) {
  String t = petToken();
  if (t != null && !t.isEmpty()) c.setRequestProperty("x-pet-token", t);
}
```

### 4.2 投递一句话

```java
private String postToInbox(String channel, String text) {
  HttpURLConnection c = null;
  try {
    String body = "{\"channel\":\"" + channel + "\",\"text\":\""
        + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    c = (HttpURLConnection) new java.net.URL(inboxUrl("/send")).openConnection();
    c.setRequestMethod("POST");
    c.setConnectTimeout(4000);
    c.setReadTimeout(8000);
    c.setDoOutput(true);
    applyAuth(c);
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    byte[] out = body.getBytes("UTF-8");
    c.setFixedLengthStreamingMode(out.length);
    java.io.OutputStream os = c.getOutputStream();
    os.write(out);
    os.close();
    // ... 读响应，非 200 返回 "HTTP <code>"，由上层决定是否进独立模式
  } catch (Throwable e) {
    return "err=" + e;                       // 上层据此降级
  } finally {
    if (c != null) c.disconnect();
  }
}
```

### 4.3 第一次绑定

```java
private void bindInbox() {
  new Thread(() -> {
    try {
      String token = getSharedPreferences("pet", MODE_PRIVATE).getString("token", "");
      HttpURLConnection c = (HttpURLConnection) new java.net.URL(inboxUrl("/bind")).openConnection();
      c.setRequestMethod("POST");
      c.setConnectTimeout(3000);
      c.setReadTimeout(6000);
      c.setDoOutput(true);
      c.setRequestProperty("content-type", "application/json");
      if (!token.isEmpty()) c.setRequestProperty("x-pet-token", token);
      c.setRequestProperty("x-pet-device", android.os.Build.MODEL == null ? "phone" : android.os.Build.MODEL);
      byte[] out = "{}".getBytes("UTF-8");
      c.setFixedLengthStreamingMode(out.length);
      java.io.OutputStream os = c.getOutputStream();
      os.write(out);
      os.close();
      // 响应里的 token 存进 SharedPreferences("pet").token
    } catch (Throwable e) {
      Log.w(TAG, "绑定工作台失败", e);
    }
  }).start();
}
```

### 4.4 缓存服务商配置（脱离 DSH 也能对话）

```java
/** 从工作台拉一次服务商配置并缓存 */
private void syncProviderFromDsh() {
  new Thread(() -> {
    String body = httpGet(inboxUrl("/provider"), 10000);
    if (body == null || !body.contains("\"ok\":true")) return;
    // 取出 base / key / model 存进 SharedPreferences("pet")：durl / dkey / dmodel
  }).start();
}
```

### 4.5 独立模式：直连 OpenAI 兼容接口

```java
/** 先试 base/chat/completions，404 再补 /v1 重试（兼容 https://api.deepseek.com 这类裸域名） */
private String postChatJson(String base, String key, String req) {
  String r = postOnce(base + "/chat/completions", key, req);
  if (r != null && r.startsWith("HTTP 404")) r = postOnce(base + "/v1/chat/completions", key, req);
  return r;
}
```

完整实现见 `android/app/src/main/java/com/dsh/petoverlay/PetService.java`（`standaloneAsk` / `standaloneWhisper` / `postChatJson`）。

---

## 5. 插件侧行为

插件 pet-inbox **不开源**，源码不在本仓库（随 App 内置分发）。这里只描述它的对外行为，方便理解协议：

- 投递走 DSH 的 `sessionController.commands.prompt()`，`mode:"queue"`（不打断正在跑的回合），`clientTimeZone:"Asia/Shanghai"`；
- `chat` / `work` 各绑一个会话，绑定失效（会话被删）时解绑后用新会话重试一次；
- 鉴权：请求令牌对不上 `bind.json` 就不干活。

> 也就是说：**App 只负责把字送进会话，干活的是 DSH 里的 agent**。

---

## 6. 首次绑定时序

1. App 启动 → `POST /bind`（无令牌）→ 插件生成 16 字节 hex 令牌并写入 `bind.json`；
2. App 把令牌存进 `SharedPreferences("pet").token`；
3. App `GET /provider` → 缓存 `base / key / model`（用于断连时的独立模式）；
4. 之后每句话 `POST /send {channel,text}` → 插件写进对应会话 → agent 回复 → App 轮询 `GET /last?channel=` 拿回来显示。

---

## 7. 排障

| 症状 | 原因 | 处理 |
| --- | --- | --- |
| `POST /send` 返回 **HTTP 405** | 3081 被别的服务占了（例如 DSH 宿主自己起在 3081） | 宿主回 3080，或两端一起改到 3082 |
| 插件日志 `EADDRINUSE` | 同上，端口冲突 | 同上 |
| `{ok:false, reason:"token-mismatch"}` | App 里存的令牌与 `bind.json` 不一致 | 删除 App 数据重新 `/bind`，或手动清 `bind.json` |
| 连不上但手机明明能上网 | 插件只绑 `127.0.0.1` | 设计如此；跨设备请自己加隧道 |
| `/send` 成功但没回复 | 绑定的会话被删/被归档 | `GET /sessions` 选新会话，`POST /channels` 换绑 |
| 回复是空的 | agent 只出了 thinking / 工具调用 | 看 DSH 工作台里该会话的完整回合 |

---

## 8. 安全

- 插件只监听 **127.0.0.1**，无 TLS（本机通信，不需要）。
- 令牌 16 字节随机 hex，存 `~/.dsh/pet-inbox/bind.json`（600 权限）。
- App 侧令牌存 `SharedPreferences`，仅本应用可读。
- 无 DSH 时走独立模式，API Key 存在 App 私有存储里，不写日志。
