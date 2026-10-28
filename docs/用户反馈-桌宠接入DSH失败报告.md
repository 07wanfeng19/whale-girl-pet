# 桌宠接入 DSH 失败报告

> 本文档是一位网友用户提供的反馈（由阿噜整理），在此表示感谢。正是这份报告帮助我们定位并修复了 0.2.1 内核的兼容性问题。

- 日期：2026-10-27
- 设备：OnePlus PJE110 / Android 16
- DSH App：0.2.1-rc.2（APP_CODE 190，proot）
- 桌宠：鲸鱼娘 1.37 实验版（dsh.petoverlay）
- 接入插件：dsh-pet-inbox 1.0.0（作者仓库 `07wanfeng19/whale-girl-pet` 的 `dsh-plugin/pet-inbox`）
- Node：v24.19.0

## 现象

桌宠能正常绑定（POST /bind 成功），也能把消息发到本地 3081；但 DSH 侧投递失败，
桌宠气泡里收不到回复。插件日志显示 `prompt rejected`。

## 复现

1. 桌宠设置里「指令入口」填 `127.0.0.1:3081`，保存并启动
2. 长按桌宠 → 聊天 → 发送任意文字
3. 插件日志出现投递失败

## 关键日志

```
[2026-10-23T07:52:02.059Z] 桌宠 APK 已绑定（沿用原令牌）
[2026-10-23T07:52:11.031Z] 通道 chat 绑定到已有会话 session-ad3d696c-...
[2026-10-23T07:52:11.033Z] 投递失败 [chat]: prompt rejected |
  reason=session/agent-busy |
  details={"reason":"Error: session event \"agent/inbox/spliced\" carries non-JSON-serializable data"}
```

换一个空闲的、全新会话（session-ad3d696c）投递，报错相同，可排除「会话忙」。

## 定位

插件走的是 `ctx.sessionController.commands.prompt({ requestId, sessionId, mode: 'queue', content, clientTimeZone })`。

在新内核 0.2.1 里，这条路径最终会落到 `agent.followup(message)`，触发
`session.append("agent/inbox/spliced", splice)`；append 会对事件数据做严格 JSON 快照，
遇到不可序列化的值时抛错，外层统一包成 `session/agent-busy: prompt rejected`。

也就是说：插件侧传进去的 `UserMessage`（或 splice 里的 `inserted`）在新版下带了
不可 JSON 序列化的字段，导致事件写不进会话。旧内核 0.2.0 上同样的调用是通的。

## 已尝试

1. 把 `message` 先 `JSON.parse(JSON.stringify())` 再 `followup` —— 无效
2. 直接改 DSH 自带 `dsh-api-session-controller` 的源码 —— 改动在 App 启动时被还原，不生效
3. 改绑到空闲会话、换通道、重新绑定 —— 均无效，错误相同

## 建议（需要插件作者改）

- 改用 0.2.1 的进程内接口投递，绕开 RPC 事件的 JSON 序列化边界
- 或核对新版 `UserMessage` / `source` 的字段，去掉 `undefined`、`Date`、类实例等不可序列化值
- 上游修复前，桌宠可先用「独立模式」直连 API 聊天

## 临时方案

桌宠设置 → 独立模式：填 API base、key、model，即可在没有 DSH 时正常对话。
DSH 开着时插件仍会尝试投递，失败后回落到独立模式。

---

（报告由阿噜整理，日志取自 `/root/.dsh/pet-inbox/plugin.log`，未包含任何凭证。）
