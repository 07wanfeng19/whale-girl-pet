# 鲸鱼娘桌宠 · Whale Girl Pet Overlay

一个跑在 Android 上的桌面宠物：**悬浮窗 + 24fps 视频渲染 + 可接 DSH 工作台/任意 OpenAI 兼容接口**。
她会走动、说话、做动作；连上工作台时能真正执行命令、读写文件；连不上时自动降级为独立模式（直连服务商），再不行就只剩卖萌。

> 当前版本：`1.1 正式版`（versionName 1.1，versionCode 157 起）· 面向 Android 11+（targetSdk 35）· 已在 ColorOS / Android 15 上实测。

---

## 0. 素材来源与作品边界

> **鲸鱼娘模型素材全部来自 npm 包 [`dsh-pet`](https://www.npmjs.com/package/dsh-pet)**（上游仓库 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，MIT）。
> **本作品只负责 Android 端生态适配**，不制作、不主张这些素材的著作权。

具体来说：

- `android/app/src/main/assets/` 下的 **webm 动画、表情包（memes）、立绘（pic）、字体（fonts）与 `config.jsonc` 人设**，均取自 `dsh-pet` 及其发行包；
- 本仓库做的是 **Android 端适配层**：悬浮窗与前台服务、MediaPlayer→SurfaceTexture→GLSurfaceView 渲染、顶点着色器里的侧倾/视线、气泡与面板交互、与 DSH（pet-inbox 插件）及 OpenAI 兼容接口的通信、无障碍自动发送、打包签名与安装；
- 对素材只做**运行时适配**（24fps 播放、按文件名前缀调度的动画/表情/余额动画），不改素材内容、不主张其版权；
- 上游素材若更新，直接替换 `assets/` 对应目录即可同步；素材的商用与再分发请遵守上游许可与实际权利人要求。

---

## 1. 它长什么样、能干什么

| 能力 | 说明 |
| --- | --- |
| 悬浮窗 | `SYSTEM_ALERT_WINDOW` 前台服务，跨应用悬浮，可拖动、可隐藏 |
| 动画 | 106 段 VP9/WebM 素材（24fps，640×360），MediaPlayer → SurfaceTexture → GLSurfaceView 渲染 |
| 侧倾 / 视线 | 在 **顶点着色器** 里做（`VideoGlView`），因为 GLSurfaceView 吃不到 View 的 matrix 变换 |
| 拖拽手感 | 拖拽期间不换片不重排窗口；松手带惯性甩出，按力度 0~3 次反复弹墙直到动能消退，撞墙带侧倾脉冲 |
| 特效完整展示 | 106 段动画的窗口包围盒全部按 ffmpeg cropdetect 逐帧实测重写，蝴蝶/气球/烟花等特效不再被窗口裁掉 |
| 说话 | 气泡显示 + 打字机效果；有气泡时工作状态汇报自动让位，不顶掉聊天回复 |
| 聊天面板 | 长按桌宠 → 对话窗；双击 → 功能面板（余额、换装、静音、发到 DeepSeek…） |
| 连 DSH | 通过本地 pet-inbox 插件（随 App 内置、不开源）把话送进 DeepSeek Harness 会话，由 agent 真执行命令 |
| 独立模式 | 没有 DSH 时直连 OpenAI 兼容接口（`/chat/completions`，自动补 `/v1`），保留基本对话 |
| 外发通道 | 一键把当前回复 `ACTION_SEND` 给 DeepSeek App；装了无障碍服务后**自动按发送** |

---

## 2. 目录结构

```
android/             Android 工程（无 Gradle 依赖也可构建，见第 6 节）
  app/src/main/
    java/com/dsh/petoverlay/
      PetService.java          悬浮窗 + 会话 + 动作调度 + DSH 客户端 + 独立模式
      CommandActivity.java     聊天/工作对话窗
      MainActivity.java        设置页（5 张配置卡片）+ 启动入口
      VideoGlView.java         GL 渲染 + 取帧 + 侧倾/视线着色器
      DeepSeekShare.java       发到 DeepSeek App（ACTION_SEND）
      DeepSeekAutoSend.java    无障碍服务：替用户点「发送」
      SecretBox.java           API Key 加密存储（AES/GCM）
      PluginInjector.java      工作台插件植入（随 App 内置、可一键安装）
      PetConfig.java / ClipBounds.java / FancyText.java / PetActionReceiver.java
    res/                       布局、图标、无障碍配置、文案
    assets/                    webm 动画 / memes 表情包 / pic / fonts / config.jsonc / pet-inbox 插件
build/
  build_apk.sh              手动打包链（aapt2 → javac → d8 → zipalign → apksigner → 装机）
  preflight.sh              打包前自检（关键修复在位、素材完整、地图准确）
docs/DSH-接入.md           与 DSH 的完整协议、代码块、排障
docs/开发日志与基线.md    决策、已关闭事项、逐版日志
docs/代码地图.md          自动生成的代码结构索引（行号对应当前源码）
docs/用户反馈-桌宠接入DSH失败报告.md   一位网友提供的反馈（已据此修复兼容性）
CHANGELOG.md               逐版更新说明
```

---

## 3. 与 DeepSeek Harness（DSH）的连接

一句话：**App 不认识 DSH 的内部结构，只认一个本地 HTTP 小服务**。这个服务由 pet-inbox 插件提供（插件不开源，随 App 内置分发）。

```
┌─────────────┐   POST /send {channel,text}   ┌──────────────────┐
│ 鲸鱼娘 App   │ ────────────────────────────▶ │ pet-inbox 插件    │
│ (Android)    │ ◀──────────────────────────── │ 127.0.0.1:3082   │
└─────────────┘   {ok:true, sessionId}         └────────┬─────────┘
                                                        │ 写入 session
                                                ┌───────▼─────────┐
                                                │ DSH 宿主 (agent) │
                                                └─────────────────┘
```

App 侧的核心调用（`PetService.postToInbox`，完整版见源码）：

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
    applyAuth(c);                                  // 头：x-pet-token: <绑定令牌>
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    ...
```

- 端口默认 **3082**，可用环境变量 `PET_INBOX_PORT` 改；只绑回环地址。
- 令牌在首次 `/bind` 时由插件发下、App 存进 `SharedPreferences("pet").token`，之后每个请求带 `x-pet-token`。
- 通道两种：`chat`（陪聊）与 `work`（干活），分别落到不同的 agent 会话。
- 详细端点（`/ping` `/diag` `/provider` `/balance` `/whisper` `/workstatus` `/bind` `/unbind` `/last`）与排障见 **[docs/DSH-接入.md](docs/DSH-接入.md)**。

### 装插件（在 DSH 里）

插件 pet-inbox **不开源**，随 App 内置（`assets/pet-inbox/`），不在本仓库公开源码。
首次在鲸鱼娘设置页点「安装 / 更新插件到工作台」，App 会把插件文件解包到中转目录，
并生成一条命令，你在 DeepSeek Harness（DSH）里贴一下执行即完成安装；启用后监听 `127.0.0.1:3082`。

### App 侧配置（设置页卡片）

| 卡片 | 填什么 |
| --- | --- |
| ① 基础 | 缩放、透明度、位置 |
| ② 模型 | 显示名 / 语气 |
| ③ 素材 | 动画目录、表情包 |
| ④ 指令入口 | 工作台地址（默认 `http://127.0.0.1:3082`）、端口 |
| ⑤ 独立模式 | `base`（OpenAI 兼容地址）、`key`、`model` —— 没有 DSH 时靠它对话 |

---

## 4. 独立模式（没有 DSH 也能用）

`PetService.standaloneAsk()` / `CommandActivity.tryOffline()`：

1. 先按 `x-pet-token` 正常投递；`IOException` 或非 200 时进入独立模式；
2. 直接 POST `${base}/chat/completions`，**404 自动补 `/v1` 重试**（兼容 `https://api.deepseek.com` 这类裸域名）；
3. 带上人设提示词（不说 Markdown / 承认自己不能执行命令），保留 3000 字上下文；
4. 再失败才显示「没有主机，独立模式也没配置」。

> 实测：宿主占着 3082 时，`/send` 得到 HTTP 405 → 自动降级直连服务商 → 200 → 气泡正常出话。

---

## 5. 发到 DeepSeek App（外发通道）

- 点「📤 发到 DeepSeek」→ `ACTION_SEND` 给 `com.deepseek.chat`，DeepSeek 新建会话并预填；
- 打开设置里的「自动发送」并授予无障碍权限后，`DeepSeekAutoSend` 会在 **15 秒窗口内**、**仅当 DeepSeek 前台**、**且屏幕上出现我们塞的那段文字**时，替用户点「发送」，点完立即解除武装；
- 关掉无障碍 → 退化为「只发不点」，不报错。

---

## 6. 构建

无 Gradle 也能打：`build/build_apk.sh <versionCode>` 走 aapt2 → javac(JDK 21) → d8 → zipalign → apksigner → `pm install`。
需要：Android SDK build-tools 36、platform android-37、JDK 21、按需改脚本里的绝对路径。

```bash
bash build/build_apk.sh 157     # 产出鲸鱼娘1.1正式版.apk（versionName 固定 1.1，versionCode=157）
```

打包前会跑 `preflight.sh`：关键修复在位、素材数量完整、代码地图准确，不通过直接终止。

---

## 7. 已知限制

- **24fps 是素材上限**，不是性能瓶颈；补帧会边缘重影，已放弃。
- **随机眨眼做不了**：WebM 无单帧控制；要眨眼得换 Live2D 实时渲染（路线已封存）。
- 自由移动已实现，**贴边吸附已取消**。
- 自动发送依赖无障碍权限；**ColorOS 每次装新包会清空无障碍授权**，需要重新打开。

---

## 8. 更新日志

逐版本改动见 [CHANGELOG.md](CHANGELOG.md)。

---

## 9. License

代码：MIT（见 [LICENSE](LICENSE)）。
素材版权单独说明：`assets/` 里的视频、表情包、字体仅随本项目分发，版权归各自作者/素材来源所有，**不适用 MIT**；二次分发请自行确认授权。

---

## English

An Android desktop-pet overlay: floating window, 24fps WebM playback through GLSurfaceView (tilt/gaze done in the vertex shader), chat bubble, command panel, and a local bridge to DeepSeek Harness (or any OpenAI-compatible endpoint as a fallback). MIT licensed code; bundled media assets keep their original rights. Build with `build/build_apk.sh <versionCode>` — no Gradle required.
