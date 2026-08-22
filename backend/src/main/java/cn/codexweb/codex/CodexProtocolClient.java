package cn.codexweb.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodexProtocolClient {
    private static final Logger log = LoggerFactory.getLogger(CodexProtocolClient.class);
    public interface Listener {
        void onMessage(String method, JsonNode params, Long requestId);
        void onClosed(String reason);
    }

    private final ObjectMapper mapper;
    private final Process process;
    private final Listener listener;
    private final BufferedWriter writer;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<Long, CompletableFuture<JsonNode>>();
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "codex-app-server-request");
        thread.setDaemon(true);
        return thread;
    });

    public CodexProtocolClient(ObjectMapper mapper, Process process, Listener listener) throws IOException {
        this.mapper = mapper; this.process = process; this.listener = listener;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        startReader();
    }

    private void startReader() {
        Thread thread = new Thread(() -> {
            String reason = "app-server 已停止";
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JsonNode message;
                    try { message = mapper.readTree(line); }
                    catch (Exception exception) { log.warn("无法解析 Codex app-server 输出: {}", line, exception); continue; }
                    if (message.has("id") && (message.has("result") || message.has("error"))) {
                        long id = message.get("id").asLong();
                        CompletableFuture<JsonNode> future = pending.remove(id);
                        if (future != null) {
                            if (message.has("error")) future.completeExceptionally(new IllegalStateException(message.get("error").toString()));
                            else future.complete(message.get("result"));
                        }
                    } else if (message.has("method")) {
                        Long requestId = message.has("id") ? message.get("id").asLong() : null;
                        listener.onMessage(message.get("method").asText(), message.get("params"), requestId);
                    }
                }
                reason = "app-server 输出流已关闭";
            } catch (IOException exception) { reason = exception.getMessage() == null ? reason : exception.getMessage(); }
            log.warn("Codex app-server 读取线程结束: pendingRequests={}, reason={}", pending.size(), reason);
            for (CompletableFuture<JsonNode> future : pending.values()) future.completeExceptionally(new IllegalStateException(reason));
            pending.clear(); listener.onClosed(reason);
        }, "codex-app-server-reader");
        thread.setDaemon(true); thread.start();
    }

    public JsonNode request(String method, Object params) {
        return request(method, params, 30);
    }

    private JsonNode request(String method, Object params, long timeoutSeconds) {
        long id = ids.getAndIncrement();
        ObjectNode message = mapper.createObjectNode(); message.put("id", id); message.put("method", method); message.set("params", mapper.valueToTree(params));
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>(); pending.put(id, future);
        try {
            synchronized (writer) { writer.write(mapper.writeValueAsString(message)); writer.write("\n"); writer.flush(); }
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception exception) {
            pending.remove(id);
            log.warn("Codex 请求失败: method={}, requestId={}, timeoutSeconds={}", method, id, timeoutSeconds, exception);
            throw new IllegalStateException("Codex 请求失败: " + method, exception);
        }
    }

    public void respond(Long id, Object result) {
        if (id == null) return;
        ObjectNode message = mapper.createObjectNode(); message.put("id", id); message.set("result", mapper.valueToTree(result));
        try { synchronized (writer) { writer.write(mapper.writeValueAsString(message)); writer.write("\n"); writer.flush(); } }
        catch (IOException exception) { throw new IllegalStateException("无法回复 Codex 请求", exception); }
    }

    public void initialize() {
        Map<String, Object> client = new HashMap<String, Object>(); client.put("name", "codex-web"); client.put("version", "0.1.0");
        Map<String, Object> params = new HashMap<String, Object>(); params.put("clientInfo", client);
        Map<String, Object> capabilities = new HashMap<String, Object>(); capabilities.put("experimentalApi", true);
        params.put("capabilities", capabilities); request("initialize", params);
    }

    public JsonNode startThread(String cwd, String approvalPolicy, String model) {
        return request("thread/start", threadParams(cwd, approvalPolicy, model));
    }

    public CompletableFuture<JsonNode> startThreadAsync(String cwd, String approvalPolicy, String model) {
        return requestAsync("thread/start", threadParams(cwd, approvalPolicy, model));
    }

    public CompletableFuture<JsonNode> resumeThreadAsync(String threadId) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("threadId", threadId);
        return requestAsync("thread/resume", params);
    }

    private Map<String, Object> threadParams(String cwd, String approvalPolicy, String model) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("cwd", cwd);
        params.put("approvalPolicy", approvalPolicy);
        if (model != null && !model.trim().isEmpty()) params.put("model", model.trim());
        // Codex 0.147.0 expects the legacy thread/start field `sandbox`.
        // Sending the newer `sandboxPolicy` object is silently ignored, which
        // causes a configured full-access thread to fall back to workspace-write.
        params.put("sandbox", "never".equals(approvalPolicy) ? "danger-full-access" : "workspace-write");
        return params;
    }

    public JsonNode startTurn(String threadId, String text) { return startTurn(threadId, text, java.util.Collections.<String>emptyList(), null); }

    public JsonNode startTurn(String threadId, String text, java.util.List<String> attachments, String reasoningEffort) {
        return request("turn/start", turnParams(threadId, text, attachments, reasoningEffort));
    }

    /**
     * turn/start can keep its JSON-RPC response open until the turn has made
     * progress. The app-server notifications are the authoritative stream,
     * so sending without blocking keeps the HTTP request responsive.
     */
    public CompletableFuture<JsonNode> startTurnAsync(String threadId, String text, java.util.List<String> attachments, String reasoningEffort) {
        final Map<String, Object> params = turnParams(threadId, text, attachments, reasoningEffort);
        return requestAsync("turn/start", params);
    }

    private CompletableFuture<JsonNode> requestAsync(String method, Object params) {
        return CompletableFuture.supplyAsync(() -> request(method, params, 120), requestExecutor);
    }

    private Map<String, Object> turnParams(String threadId, String text, java.util.List<String> attachments, String reasoningEffort) {
        Map<String, Object> input = new HashMap<String, Object>(); input.put("type", "text"); input.put("text", text);
        java.util.List<Map<String, Object>> inputs = new java.util.ArrayList<Map<String, Object>>(); inputs.add(input);
        if (attachments != null) for (String path : attachments) {
            if (path == null || path.trim().isEmpty()) continue;
            String lower = path.toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp")) {
                Map<String, Object> image = new HashMap<String, Object>(); image.put("type", "localImage"); image.put("path", path); inputs.add(image);
            } else input.put("text", String.valueOf(input.get("text")) + "\n\n附件路径：" + path);
        }
        Map<String, Object> params = new HashMap<String, Object>(); params.put("threadId", threadId); params.put("input", inputs);
        if (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) params.put("effort", reasoningEffort.trim());
        return params;
    }

    public JsonNode steerTurn(String threadId, String turnId, String text, java.util.List<String> attachments) {
        return request("turn/steer", steerParams(threadId, turnId, text, attachments));
    }

    public CompletableFuture<JsonNode> steerTurnAsync(String threadId, String turnId, String text, java.util.List<String> attachments) {
        return requestAsync("turn/steer", steerParams(threadId, turnId, text, attachments));
    }

    private Map<String, Object> steerParams(String threadId, String turnId, String text, java.util.List<String> attachments) {
        Map<String, Object> input = new HashMap<String, Object>(); input.put("type", "text"); input.put("text", text);
        java.util.List<Map<String, Object>> inputs = new java.util.ArrayList<Map<String, Object>>(); inputs.add(input);
        if (attachments != null) for (String path : attachments) {
            if (path == null || path.trim().isEmpty()) continue;
            String lower = path.toLowerCase();
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp")) {
                Map<String, Object> image = new HashMap<String, Object>(); image.put("type", "localImage"); image.put("path", path); inputs.add(image);
            } else input.put("text", String.valueOf(input.get("text")) + "\n\n附件路径：" + path);
        }
        Map<String, Object> params = new HashMap<String, Object>(); params.put("threadId", threadId); params.put("expectedTurnId", turnId); params.put("input", inputs); return params;
    }

    public void interrupt(String threadId, String turnId) {
        Map<String, Object> params = new HashMap<String, Object>(); params.put("threadId", threadId); params.put("turnId", turnId); request("turn/interrupt", params);
    }

    public CompletableFuture<JsonNode> interruptAsync(String threadId, String turnId) {
        Map<String, Object> params = new HashMap<String, Object>(); params.put("threadId", threadId); params.put("turnId", turnId);
        return requestAsync("turn/interrupt", params);
    }

    public void close() {
        log.info("关闭 Codex app-server: pendingRequests={}", pending.size());
        try { writer.close(); } catch (IOException ignored) { }
        if (!process.isAlive()) return;
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy();
            if (process.isAlive() && !process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        requestExecutor.shutdownNow();
    }
}
