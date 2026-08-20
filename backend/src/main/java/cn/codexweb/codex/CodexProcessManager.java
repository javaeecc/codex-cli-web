package cn.codexweb.codex;

import cn.codexweb.config.CodexWebProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CodexProcessManager {
    private static final Logger log = LoggerFactory.getLogger(CodexProcessManager.class);
    private final CodexWebProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private CodexProtocolClient client;
    private Process process;
    private String startedAt;
    private String version;
    private String lastError;
    private long lifecycle;

    public CodexProcessManager(CodexWebProperties properties) { this.properties = properties; }

    public synchronized CodexProtocolClient ensureStarted(CodexProtocolClient.Listener listener) {
        if (client != null && process != null && process.isAlive()) return client;
        start(listener); return client;
    }

    public synchronized void start(CodexProtocolClient.Listener listener) {
        if (client != null && process != null && process.isAlive()) return;
        try {
            final long currentLifecycle = ++lifecycle;
            String command = properties.getCodexCommand();
            String argument = command.toLowerCase().endsWith(".cmd") ? command + " app-server --stdio" : command + " app-server --stdio";
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", argument);
            builder.redirectErrorStream(true);
            process = builder.start();
            log.info("启动 Codex app-server: command={}, lifecycle={}, pid={}", command, currentLifecycle, pid(process));
            client = new CodexProtocolClient(mapper, process, new CodexProtocolClient.Listener() {
                public void onMessage(String method, com.fasterxml.jackson.databind.JsonNode params, Long requestId) { listener.onMessage(method, params, requestId); }
                public void onClosed(String reason) {
                    synchronized (CodexProcessManager.this) {
                        if (lifecycle != currentLifecycle) return;
                        lastError = reason;
                        log.warn("Codex app-server 已退出: lifecycle={}, reason={}", currentLifecycle, reason);
                        client = null;
                        process = null;
                    }
                    listener.onClosed(reason);
                }
            });
            client.initialize();
            startedAt = java.time.Instant.now().toString(); version = "0.147.0"; lastError = null;
            log.info("Codex app-server 初始化完成: lifecycle={}, version={}", currentLifecycle, version);
        } catch (Exception exception) {
            lastError = exception.getMessage(); if (process != null && process.isAlive()) process.destroy(); client = null; process = null;
            log.error("启动 Codex app-server 失败: command={}", properties.getCodexCommand(), exception);
            throw new IllegalStateException("无法启动 Codex app-server", exception);
        }
    }

    public synchronized void stop() {
        log.info("停止 Codex app-server: pid={}, lifecycle={}", process == null ? null : pid(process), lifecycle);
        lifecycle++;
        Process target = process;
        if (client != null) client.close();
        if (target != null && target.isAlive()) terminateProcessTree(target);
        client = null;
        process = null;
    }
    public synchronized Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("running", client != null && process != null && process.isAlive()); result.put("pid", process == null ? null : pid(process));
        result.put("version", version); result.put("startedAt", startedAt); result.put("lastError", lastError); result.put("command", properties.getCodexCommand());
        return result;
    }
    public synchronized CodexProtocolClient client() { return client; }

    private Long pid(Process value) {
        try {
            java.lang.reflect.Method method = Process.class.getMethod("pid");
            Object result = method.invoke(value);
            return result instanceof Number ? ((Number) result).longValue() : null;
        } catch (Exception ignored) {
            try {
                Field field = value.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                Object result = field.get(value);
                return result instanceof Number ? ((Number) result).longValue() : null;
            } catch (Exception ignoredAgain) { return null; }
        }
    }

    private void terminateProcessTree(Process target) {
        Long processId = pid(target);
        if (processId == null) {
            target.destroyForcibly();
            return;
        }
        try {
            Process killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(processId), "/T", "/F").redirectErrorStream(true).start();
            killer.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            target.destroyForcibly();
        }
    }
}
