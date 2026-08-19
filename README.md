# Codex Web Workbench

个人使用的本机 Codex Web 工作台。后端使用 Java 8 + Spring Boot 2.7，前端使用 Vue 2 + Element UI，通过 Codex CLI app-server 执行任务，使用 SSE 推送实时事件。

## 启动

后端：

```powershell
cd backend
mvn spring-boot:run
```

前端：

```powershell
cd frontend
npm run serve
```

浏览器访问 `http://127.0.0.1:9000`。后端默认使用 `http://127.0.0.1:8090`，因为本机 `8080` 已被其他 Java 服务占用。

## 前置条件

- JDK 8
- Maven 3.6+
- Node.js 18+
- Git
- Codex CLI 0.147.0+
- 已完成 Codex 登录

可从系统磁盘根目录浏览并选择任意本地工作空间，Codex 命令为 `codex.cmd`。如需指定绝对路径或远程访问参数，可设置：

```powershell
$env:CODEX_COMMAND = 'C:\nvm4w\nodejs\codex.cmd'
$env:CODEX_WEB_HOST = '127.0.0.1'
$env:CODEX_WEB_PORT = '8080'
$env:CODEX_WEB_TOKEN = 'change-this-token'
```

远程访问前必须配置访问令牌、监听地址和网络边界。拥有工作台访问权限的人可以操作本机 Codex 和项目文件。

## 打包

```powershell
cd backend
mvn clean package
cd ..\frontend
npm run build
```

更多协议见 `docs/codex-app-server.md` 和 `docs/websocket-protocol.md`。
