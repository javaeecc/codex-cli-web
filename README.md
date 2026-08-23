# Codex Web Workbench

> 把电脑上的 Codex 变成可以用手机远程控制的 AI 编程工作台。

项目运行在你的电脑上，Codex 也运行在你的电脑上；手机只需要打开一个网页，就能远程发送任务、查看实时输出、处理审批、查看代码修改和 Git 差异。

配置好 FRP 外网映射后，即使人不在电脑旁边，也可以在家里、路上或其他地方远程控制办公室电脑上的 Codex。电脑继续负责执行命令和修改文件，手机负责下达指令和查看结果。

## 一句话体验

项目下载并完成首次环境准备后，日常只要启动前端、后端和 FRP 客户端，手机打开公网地址即可使用 Codex。无需在手机上安装 Codex，无需把项目复制到手机，也无需让手机承担编译和命令执行。人在家里躺着，也能控制电脑上的 Codex 干活。

## 工作原理

```text
手机浏览器
    │ HTTPS / FRP 公网入口
    ▼
FRP Server（公网服务器）
    │ FRP 隧道
    ▼
电脑上的前端 :9000
    │ /api 代理
    ▼
电脑上的后端 :8090
    │ stdio JSON-RPC
    ▼
Codex CLI app-server
    │
    ▼
本机项目文件、命令行和 Git
```

后端通过 `codex.cmd app-server --stdio` 接入本机 Codex CLI。前端会把 `/api` 请求代理到后端，因此推荐只用 FRP 映射前端入口 `9000`，手机访问一个地址即可操作完整工作台，不要把后端 `8090` 单独暴露到公网。

## 手机远程使用流程

1. 在电脑上安装并登录 Codex CLI，启动本项目的前端和后端。
2. 在公网服务器启动 FRP Server，在电脑上启动 FRP Client，把电脑的 `9000` 映射出去。
3. 手机浏览器访问 FRP 公网域名或地址，登录工作台。
4. 选择电脑上的项目，发送任务、继续会话或取消任务。
5. Codex 在电脑上执行，手机通过 SSE 实时查看回复、命令输出、审批请求和文件变更。

## FRP 示例

公网服务器 `frps.toml`：

```toml
bindPort = 7000
```

电脑上的 `frpc.toml`（FRP Server 与 Client 版本保持一致）：

```toml
serverAddr = "你的公网服务器地址"
serverPort = 7000

[[proxies]]
name = "codex-web"
type = "http"
localIP = "127.0.0.1"
localPort = 9000
customDomains = ["codex.example.com"]
```

启动命令：

```bash
./frps -c ./frps.toml
```

```powershell
frpc.exe -c .\frpc.toml
```

启动后，手机访问 `https://codex.example.com`。如果暂时没有域名，也可以使用 FRP 映射的公网 IP 和端口。正式对外访问建议配置 HTTPS、防火墙和额外的访问控制。

| 地址 | 用途 | FRP 建议 |
| --- | --- | --- |
| 电脑 `127.0.0.1:9000` | Vue 工作台入口，代理 `/api` | 映射这个端口 |
| 电脑 `127.0.0.1:8090` | Spring Boot 后端 API | 不单独暴露 |
| FRP Server `7000` | FRP 控制连接 | 只允许 FRP 客户端连接 |

## 安全提醒

远程访问等同于远程操作电脑上的 Codex 和项目文件。请修改默认登录用户名、密码和 JWT 密钥，只向可信用户开放 FRP 地址，并优先使用 HTTPS。不要把后端端口直接暴露到公网，也不要在不理解权限含义时启用 `完全访问`。

本项目由 Vue 2 前端和 Spring Boot 后端组成。后端通过本机 Codex CLI 的 `app-server --stdio` 接入 Codex，不调用 OpenAI API，也不复制 Codex Desktop 的前端资源。

## 功能概览

- 从本机文件系统选择或创建工作空间，并保存为项目
- 创建、恢复、归档和导出 Codex 会话
- 通过 SSE 实时显示 agent 消息、命令输出、变更摘要和审批请求
- 支持正在执行的回合引导（steer）、排队任务和取消任务
- 查看项目文件、文件内容、Git 状态、差异和分支，并在空闲时切换分支
- 可配置模型、推理级别和工作权限策略
- 使用 JSON 文件保存项目、会话、设置和事件，适合个人本机使用

## 技术栈

| 部分 | 技术 |
| --- | --- |
| 前端 | Vue 2.7、Vue Router、Vuex、Element UI、Webpack 5 |
| 后端 | Java 8、Spring Boot 2.7.18、Maven |
| Codex 接入 | Codex CLI 0.147.0+，stdio JSON-RPC app-server |
| 实时通信 | Server-Sent Events（SSE） |
| 持久化 | `data/` 下的 JSON 文件和事件日志 |

## 环境要求

- Windows（后端通过 `cmd.exe` 启动 `.cmd` 命令）
- JDK 8
- Maven 3.6+
- Node.js 18+ 和 npm
- Git（项目 Git 功能需要）
- Codex CLI 0.147.0 或更高版本
- 已完成 Codex CLI 登录，并能在终端直接执行 `codex.cmd`

检查 Codex CLI：

```powershell
codex.cmd --version
codex.cmd app-server --stdio
```

第二条命令会进入持续运行的 stdio 服务，确认能启动后使用 `Ctrl+C` 退出即可。

## 快速开始

在两个 PowerShell 窗口分别启动后端和前端。

### 1. 安装前端依赖

```powershell
cd frontend
npm install
```

仓库已包含 `package-lock.json`；需要严格复现依赖时可使用 `npm ci`。

### 2. 启动后端

```powershell
cd backend
mvn spring-boot:run
```

默认监听 `http://127.0.0.1:8090`。Codex runtime 不一定随 Spring Boot 一起启动，首次创建或发送会话时会按需启动，也可以在界面中手动启动/重启。

### 3. 启动前端

```powershell
cd frontend
npm run serve
```

打开 [http://127.0.0.1:9000](http://127.0.0.1:9000)。前端开发服务器会把 `/api` 请求代理到 `http://127.0.0.1:8090`。

首次登录使用后端配置的用户名和密码。默认值仅适合本机开发，使用前请通过环境变量修改。

## 配置

后端配置位于 [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml)，环境变量优先级更高：

| 环境变量 | 默认值                          | 作用 |
| --- |------------------------------| --- |
| `CODEX_WEB_HOST` | `127.0.0.1`                  | 后端监听地址 |
| `CODEX_WEB_PORT` | `8090`                       | 后端端口 |
| `CODEX_COMMAND` | `codex.cmd`                  | Codex CLI 可执行命令或绝对路径 |
| `CODEX_WEB_DATA_DIR` | `../data`                    | 项目、会话、上传和日志目录 |
| `CODEX_WEB_USERNAME` | 请自行设置                   | 登录用户名 |
| `CODEX_WEB_PASSWORD` | 请自行设置                   | 登录密码 |
| `CODEX_WEB_JWT_SECRET` | 内置开发密钥                       | JWT 签名密钥 |
| `CODEX_WEB_JWT_TTL_MILLIS` | `28800000`                   | JWT 有效期（毫秒） |
| `CODEX_WEB_LOG_FILE` | `../data/logs/codex-web.log` | 日志文件 |

示例：

```powershell
$env:CODEX_COMMAND = 'C:\nvm4w\nodejs\codex.cmd'
$env:CODEX_WEB_USERNAME = 'me@example.com'
$env:CODEX_WEB_PASSWORD = 'change-this-password'
$env:CODEX_WEB_JWT_SECRET = 'replace-with-a-long-random-secret'
$env:CODEX_WEB_HOST = '127.0.0.1'
$env:CODEX_WEB_PORT = '8090'
$env:CODEX_WEB_DATA_DIR = 'D:\codex-web-data'
$env:CODEX_WEB_LOG_FILE = 'D:\codex-web-data\logs\codex-web.log'
```

修改环境变量后需要重启后端。前端开发代理端口在 [`frontend/webpack.config.js`](frontend/webpack.config.js) 中配置；如果修改后端端口，也要同步修改该代理目标。

## 使用流程

1. 登录后在侧边栏选择一个目录，或创建新的工作空间。
2. 在项目中创建会话，选择模型、推理级别和工作权限。
3. 输入任务并发送。运行过程会通过 SSE 实时更新；任务执行中的追加消息使用 steer，普通新消息会进入队列。
4. 遇到命令或文件审批时，在会话中选择接受、拒绝或取消。
5. 在文件/Git 面板检查变更，确认项目空闲后再切换分支。

工作权限含义：`请求批准` 使用 `workspace-write` 沙箱；`帮我批准` 在失败时请求批准；`完全访问` 使用 `danger-full-access`，允许 Codex 执行工作空间外的命令。权限设置对新建会话生效。

## 项目结构

```text
backend/
  src/main/java/cn/codexweb/        Spring Boot API、Codex 协议和存储
  src/main/resources/               application.yml
frontend/
  src/                              Vue 页面、组件、API 客户端和样式
  webpack.config.js                 开发服务器及 /api 代理
docs/
  codex-app-server.md               Codex app-server 接入与事件映射
  sse-protocol.md                   SSE 和会话控制接口
  conversation-display-standard.md  对话展示约定
data/                                运行时生成的本地数据（已被 Git 忽略）
```

## API 与协议文档

所有业务接口前缀为 `/api`。常用接口包括：

- `GET /api/health`、`GET /api/runtime`：健康检查和 Codex runtime 状态
- `GET/POST /api/auth/*`：登录、当前用户和退出
- `GET/POST/PUT/DELETE /api/projects*`：项目管理
- `GET/POST/PUT/DELETE /api/sessions*`：会话、回合、审批、归档和导出
- `GET /api/sessions/{id}/stream`：SSE 实时事件流
- `GET/POST /api/workspaces*`：工作空间浏览和创建

完整的事件映射见 [`docs/codex-app-server.md`](docs/codex-app-server.md)，SSE 示例和控制接口见 [`docs/sse-protocol.md`](docs/sse-protocol.md)。

## 打包构建

```powershell
cd backend
mvn clean package

cd ..\frontend
npm run build
```

后端 JAR 输出到 `backend/target/`，前端静态文件输出到 `frontend/dist/`。当前项目没有配置 Spring Boot 静态资源托管，生产环境需要分别部署后端和前端，并将 `/api` 反向代理到后端。

## 数据、日志与安全

运行时数据默认保存在 `data/`：项目和设置为 JSON 文件，会话事件保存在 `data/sessions/`，上传文件在 `data/uploads/`，日志在 `data/logs/`。这些内容包含工作区路径、会话内容和执行结果，已通过 `.gitignore` 排除，不应提交到仓库。

默认监听地址为回环地址。若需要局域网或公网访问，请自行配置防火墙、HTTPS 和反向代理，并务必修改用户名、密码和 JWT 密钥；工作台访问者具备操作本机 Codex 和项目文件的能力。上传大小默认限制为 10 MB，Git 差异输出默认限制为 512 KB。

## 故障排查

### 页面打不开

- 确认前端命令运行在 `frontend/`，并访问 9000 端口。
- 确认后端已运行，并访问 `http://127.0.0.1:8090/api/health`。
- 如果后端端口改过，同步修改 `frontend/webpack.config.js` 的 proxy target。

### Codex runtime 无法启动

- 在终端执行 `codex.cmd --version`，确认命令在 PATH 中。
- 通过 `CODEX_COMMAND` 设置 `codex.cmd` 的绝对路径。
- 查看 [`data/logs/codex-web.log`](data/logs/codex-web.log) 中的启动错误。
- 确认 Codex CLI 已登录，并且版本支持 `app-server --stdio`。

### 会话实时内容不更新

浏览器会自动重连 SSE。先检查后端日志和浏览器控制台，再确认没有代理、网关或防火墙缓冲 `text/event-stream` 响应。也可以刷新会话，历史事件会通过 `/events` 接口恢复。

## 许可

当前仓库未声明开源许可证。如需对外发布，请补充许可证和第三方依赖声明。
