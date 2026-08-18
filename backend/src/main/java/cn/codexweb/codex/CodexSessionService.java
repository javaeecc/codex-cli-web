package cn.codexweb.codex;

import cn.codexweb.api.ApiException;
import cn.codexweb.model.Project;
import cn.codexweb.model.Session;
import cn.codexweb.model.StoredEvent;
import cn.codexweb.storage.ProjectStore;
import cn.codexweb.storage.SessionStore;
import cn.codexweb.workspace.WorkspaceGuard;
import cn.codexweb.config.CodexWebProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CodexSessionService implements CodexProtocolClient.Listener {
    private final SessionStore sessions;
    private final ProjectStore projects;
    private final CodexProcessManager processManager;
    private final SseHub hub;
    private final WorkspaceGuard guard;
    private final CodexWebProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public CodexSessionService(SessionStore sessions, ProjectStore projects, CodexProcessManager processManager, SseHub hub, WorkspaceGuard guard, CodexWebProperties properties) {
        this.sessions = sessions; this.projects = projects; this.processManager = processManager; this.hub = hub; this.guard = guard; this.properties = properties;
    }

    public List<StoredEvent> events(String sessionId) { requireSession(sessionId); return sessions.events(sessionId); }

    public synchronized void startTurn(String sessionId, String text) {
        startTurn(sessionId, text, java.util.Collections.<String>emptyList());
    }

    public synchronized void startTurn(String sessionId, String text, java.util.List<String> attachments) {
        if (text == null || text.trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_REQUIRED", "请输入任务内容");
        Session session = requireSession(sessionId);
        if ("RUNNING".equals(session.status) || "WAITING_APPROVAL".equals(session.status)) throw new ApiException(HttpStatus.CONFLICT, "SESSION_BUSY", "当前会话已有任务正在运行");
        Project project = projects.find(session.projectId);
        if (project == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        guard.requireDirectory(project.path);
        CodexProtocolClient client = processManager.ensureStarted(this);
        if (session.codexThreadId == null) {
            JsonNode response = client.startThread(project.path);
            JsonNode thread = response == null ? null : response.get("thread");
            session.codexThreadId = thread == null ? null : text(thread.get("id"));
            if (session.codexThreadId == null) throw new IllegalStateException("Codex 未返回 thread id");
            sessions.save(session);
        }
        session.lastUserMessage = text;
        session.status = "RUNNING";
        sessions.save(session);
        append(session, "turn.started", map("text", text));
        try {
            java.util.List<String> safeAttachments = new java.util.ArrayList<String>();
            if (attachments != null) for (String attachment : attachments) {
                java.nio.file.Path candidate = java.nio.file.Paths.get(attachment).toAbsolutePath().normalize();
                java.nio.file.Path uploadRoot = sessionsUploadRoot(session.id);
                if (candidate.startsWith(uploadRoot) && java.nio.file.Files.isRegularFile(candidate)) safeAttachments.add(candidate.toString());
            }
            JsonNode result;
            try {
                result = client.startTurn(session.codexThreadId, text, safeAttachments);
            } catch (RuntimeException staleThread) {
                JsonNode replacement = client.startThread(project.path);
                JsonNode replacementThread = replacement == null ? null : replacement.get("thread");
                session.codexThreadId = replacementThread == null ? null : text(replacementThread.get("id"));
                if (session.codexThreadId == null) throw staleThread;
                sessions.save(session);
                result = client.startTurn(session.codexThreadId, text, safeAttachments);
            }
            JsonNode turn = result == null ? null : result.get("turn");
            if (turn != null) { session.currentTurnId = text(turn.get("id")); sessions.save(session); append(session, "turn.accepted", map("turnId", session.currentTurnId)); }
        } catch (RuntimeException exception) {
            session.status = "FAILED"; sessions.save(session); append(session, "error", map("message", exception.getMessage())); throw exception;
        }
    }

    public synchronized void cancel(String sessionId) {
        Session session = requireSession(sessionId);
        if (session.codexThreadId == null || processManager.client() == null) return;
        if (session.currentTurnId == null) return;
        processManager.client().interrupt(session.codexThreadId, session.currentTurnId);
        session.status = "CANCELLED"; sessions.save(session); append(session, "turn.cancelled", Collections.<String, Object>emptyMap());
    }

    public void approval(String sessionId, Long requestId, String decision) {
        requireSession(sessionId);
        CodexProtocolClient client = processManager.client(); if (client == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CODEX_NOT_RUNNING", "Codex 尚未运行");
        String method = "";
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("decision", decision == null ? "decline" : decision);
        client.respond(requestId, result);
        Session session = requireSession(sessionId); session.status = "RUNNING"; sessions.save(session); append(session, "approval.responded", result);
    }

    public void subscribe(String sessionId) { requireSession(sessionId); }

    public boolean projectBusy(String projectId) {
        for (Session session : sessions.byProject(projectId)) if ("RUNNING".equals(session.status) || "WAITING_APPROVAL".equals(session.status)) return true;
        return false;
    }

    @Override
    public void onMessage(String method, JsonNode params, Long requestId) {
        if (method == null || method.toLowerCase().contains("reasoning")) return;
        String threadId = findText(params, "threadId"); if (threadId == null) threadId = findText(params, "conversationId");
        Session session = findByThread(threadId); if (session == null) return;
        String normalized = normalize(method, params);
        if (normalized == null) return;
        // Codex can emit retryable transport errors while it reconnects the same turn.
        // They are progress notifications, not terminal session failures.
        if (normalized.equals("error") && willRetry(params)) normalized = "turn.retrying";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("method", method); if (params != null) data.put("payload", mapper.convertValue(params, Map.class));
        if (requestId != null) data.put("requestId", requestId);
        String delta = findText(params, "delta"); if (delta == null) delta = findText(params, "text"); if (delta != null) data.put("text", delta);
        if (normalized.equals("turn.started")) updateStatus(session, "RUNNING");
        if (normalized.equals("approval.request")) updateStatus(session, "WAITING_APPROVAL");
        if (normalized.equals("turn.completed")) { session.currentTurnId = null; updateStatus(session, "COMPLETED"); }
        if (normalized.equals("error")) updateStatus(session, "FAILED");
        append(session, normalized, data);
    }

    @Override
    public void onClosed(String reason) {
        for (Session session : sessions.all()) if ("RUNNING".equals(session.status) || "WAITING_APPROVAL".equals(session.status)) {
            session.status = "FAILED"; sessions.save(session); append(session, "error", map("message", reason));
        }
    }

    private String normalize(String method, JsonNode params) {
        String value = method.toLowerCase();
        if (value.contains("requestapproval") || value.contains("approvalrequest") || value.contains("elicitation")) return "approval.request";
        if (value.equals("turn/started") || value.contains("turnstarted")) return "turn.started";
        if (value.equals("turn/completed") || value.contains("turncompleted")) return "turn.completed";
        if (value.contains("turn/diff") || value.contains("diffupdated")) return "diff.updated";
        if (value.contains("agentmessage") && value.contains("delta")) return "agent.message.delta";
        if (value.contains("commandexecution") && value.contains("output")) return "tool.call.output";
        if (value.contains("filechange") && (value.contains("output") || value.contains("patchupdated"))) return "tool.call.output";
        if (value.contains("item/started") || value.contains("itemstarted")) return "tool.call.started";
        if (value.contains("item/completed") || value.contains("itemcompleted")) return "tool.call.completed";
        if (value.equals("error") || value.endsWith("/error")) return "error";
        if (value.contains("thread/status") || value.contains("statuschanged")) return "session.state";
        return null;
    }

    private Session findByThread(String threadId) {
        if (threadId == null) return null;
        for (Session session : sessions.all()) if (threadId.equals(session.codexThreadId)) return session;
        return null;
    }

    private Session requireSession(String id) {
        Session session = sessions.find(id); if (session == null) throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在"); return session;
    }
    private void updateStatus(Session session, String status) { session.status = status; sessions.save(session); }
    private void append(Session session, String type, Map<String, Object> data) {
        StoredEvent event = new StoredEvent(); event.id = UUID.randomUUID().toString(); event.type = type; event.sessionId = session.id; event.timestamp = java.time.Instant.now().toString(); event.data = data;
        sessions.appendEvent(event); Map<String, Object> message = new LinkedHashMap<String, Object>(); message.put("id", event.id); message.put("type", type); message.put("sessionId", session.id); message.put("timestamp", event.timestamp); message.put("data", data); hub.publish(session.id, message);
    }
    private String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private String findText(JsonNode node, String field) { return node == null || node.get(field) == null ? null : text(node.get(field)); }
    private boolean willRetry(JsonNode params) { return params != null && params.path("willRetry").asBoolean(false); }
    private Map<String, Object> map(String key, Object value) { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key, value); return result; }
    private java.nio.file.Path sessionsUploadRoot(String sessionId) { return java.nio.file.Paths.get(properties.getDataDir()).toAbsolutePath().normalize().resolve("uploads").resolve(sessionId); }
}
