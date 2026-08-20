# Codex app-server 接入说明

本项目通过本机 Codex CLI 的 stdio transport 接入，不调用 OpenAI API，也不复制 Codex Desktop 的前端资源。

## 当前环境

- Codex CLI：0.147.0
- 启动命令：`codex.cmd app-server --stdio`
- Windows 启动：后端通过 `cmd.exe /d /c` 启动，兼容 `.cmd` 文件。
- 初始化握手：`initialize`

新版 CLI 的 JSON Schema 可通过以下命令重新生成：

```powershell
codex.cmd app-server generate-json-schema --experimental --out .tmp/app-server-schema
```

## 使用的方法

- `initialize`：声明 `codex-web` 客户端能力。
- `thread/start`：使用选中的工作空间目录创建 Codex 线程，模型、审批策略和沙箱策略由工作台设置传入，默认模型跟随 Codex 配置，默认权限是 `on-request` + `workspaceWrite`。
- `thread/resume`：按已保存的 `threadId` 从 Codex 磁盘记录恢复线程上下文，用于 app-server 重启后的会话继续执行。
- `turn/start`：向线程发送文本任务，推理级别通过 `effort` 传入，可选 `low`、`medium`、`high`、`xhigh`，留空时跟随 Codex 配置。
- `turn/steer`：向当前正在运行的 turn 注入用户引导，支持思考过程中追加消息；普通发送会进入会话队列，当前 turn 结束后按顺序执行。
- `turn/interrupt`：按 `threadId` 和 `turnId` 停止任务。

## 事件规范化

后端只把用户可见事件转发到浏览器：

| app-server 事件 | SSE 事件 |
|---|---|
| `item/agentMessage/delta` | `agent.message.delta` |
| `item/commandExecution/outputDelta` | `tool.call.output` |
| `item/started` | `tool.call.started` |
| `item/completed` | `tool.call.completed` |
| `turn/started` | `turn.started` |
| `turn/completed` | `turn.completed` |
| `turn/diff/updated` | `diff.updated` |
| `item/*/requestApproval` | `approval.request` |
| `error` | `error` |

Reasoning 和隐藏思维链事件不会进入 JSONL，也不会发送到浏览器。

## 故障排查日志

后端日志默认写入 `data/logs/codex-web.log`，按 20 MB 滚动并保留 14 个历史文件。关键日志都包含 `sessionId`、`threadId`、`turnId` 或 JSON-RPC `requestId`，包括：

- app-server 启停、初始化失败、stdio 读取结束和协议请求超时；
- 无法匹配会话或无法识别的通知事件；
- 回合启动、接受、重试、完成、失败和审批；
- SSE 建连、心跳/事件发送失败；
- 事件文件读取缓慢、增量游标失效和追加失败；
- 回合超过 15 分钟没有进展时的自动失败恢复，以及排队任务回放。

前端在浏览器控制台记录 SSE 断线和会话状态同步失败，正常状态同步不会再读取完整事件历史。

## 审批

工作台界面提供三种与 Codex 桌面版一致的工作权限：`请求批准`（`on-request`）、`帮我批准`（`on-failure`）和 `完全访问`（`never`）。前两者使用 `workspaceWrite` 沙箱；`never` 同时使用 `dangerFullAccess`，表示不请求审批并允许执行工作空间外的命令。底层仍兼容 `untrusted` 策略，但不在工作台界面展示。审批策略和沙箱策略都是线程创建参数，因此保存后对新建会话生效，已有会话需要新建会话才能使用新策略。

命令审批使用新版 app-server 响应格式：

```json
{"decision":"accept"}
```

也支持 `decline`、`cancel` 和 `acceptForSession`。文件变更审批使用对应的 `accept`、`decline`、`cancel` 决策。
