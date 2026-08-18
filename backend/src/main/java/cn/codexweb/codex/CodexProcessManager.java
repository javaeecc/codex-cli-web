package cn.codexweb.codex;

import cn.codexweb.config.CodexWebProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CodexProcessManager {
    private final CodexWebProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private CodexProtocolClient client;
    private Process process;
    private String startedAt;
    private String version;
    private String lastError;

    public CodexProcessManager(CodexWebProperties properties) { this.properties = properties; }

    public synchronized CodexProtocolClient ensureStarted(CodexProtocolClient.Listener listener) {
        if (client != null && process != null && process.isAlive()) return client;
        start(listener); return client;
    }

    public synchronized void start(CodexProtocolClient.Listener listener) {
        if (client != null && process != null && process.isAlive()) return;
        try {
            String command = properties.getCodexCommand();
            String argument = command.toLowerCase().endsWith(".cmd") ? command + " app-server --stdio" : command + " app-server --stdio";
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", argument);
            builder.redirectErrorStream(true);
            process = builder.start();
            client = new CodexProtocolClient(mapper, process, new CodexProtocolClient.Listener() {
                public void onMessage(String method, com.fasterxml.jackson.databind.JsonNode params, Long requestId) { listener.onMessage(method, params, requestId); }
                public void onClosed(String reason) { synchronized (CodexProcessManager.this) { lastError = reason; client = null; process = null; } listener.onClosed(reason); }
            });
            client.initialize();
            startedAt = java.time.Instant.now().toString(); version = "0.147.0"; lastError = null;
        } catch (Exception exception) {
            lastError = exception.getMessage(); if (process != null && process.isAlive()) process.destroy(); client = null; process = null;
            throw new IllegalStateException("无法启动 Codex app-server", exception);
        }
    }

    public synchronized void stop() { if (client != null) client.close(); client = null; process = null; }
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
        } catch (Exception ignored) { return null; }
    }
}
