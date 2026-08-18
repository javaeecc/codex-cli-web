# SSE 实时协议

浏览器通过 `GET /api/sessions/{sessionId}/stream` 建立 Server-Sent Events 连接。历史事件仍通过 `GET /api/sessions/{sessionId}/events` 获取，实时事件使用同一套 JSON 事件格式。

## SSE 事件

```text
data:{"type":"stream.ready","sessionId":"..."}

data:{"type":"agent.message.delta","sessionId":"...","data":{"text":"..."}}

data:{"type":"turn.completed","sessionId":"...","data":{}}
```

服务端每 15 秒发送一次 SSE 注释心跳，浏览器断线后由 `EventSource` 自动重连。鉴权开启时，SSE 地址支持 `?token=...`。

## HTTP 控制接口

```text
POST /api/sessions/{sessionId}/turns
POST /api/sessions/{sessionId}/cancel
POST /api/sessions/{sessionId}/approval
```

任务、停止和审批使用 JSON 请求体，实时执行结果统一通过 SSE 推送。
