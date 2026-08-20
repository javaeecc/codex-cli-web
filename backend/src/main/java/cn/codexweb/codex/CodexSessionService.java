package cn.codexweb.codex;

import cn.codexweb.api.ApiException;
import cn.codexweb.model.Project;
import cn.codexweb.model.Session;
import cn.codexweb.model.StoredEvent;
import cn.codexweb.model.AppSettings;
import cn.codexweb.model.QueuedTurn;
import cn.codexweb.model.SessionHistory;
import cn.codexweb.storage.ProjectStore;
import cn.codexweb.storage.AppSettingsStore;
import cn.codexweb.storage.SessionStore;
import cn.codexweb.workspace.WorkspaceGuard;
import cn.codexweb.config.CodexWebProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CodexSessionService implements CodexProtocolClient.Listener {
    private static final Logger log = LoggerFactory.getLogger(CodexSessionService.class);
    private static final long STALL_TIMEOUT_MILLIS = 15L * 60L * 1000L;
    private final SessionStore sessions;
    private final ProjectStore projects;
    private final CodexProcessManager processManager;
    private final SseHub hub;
    private final WorkspaceGuard guard;
    private final CodexWebProperties properties;
    private final AppSettingsStore appSettings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> threadSessions = new ConcurrentHashMap<String, String>();
    private final Map<String, Long> pendingThreadStarts = new ConcurrentHashMap<String, Long>();
    private final Map<String, PendingTurn> pendingTurns = new ConcurrentHashMap<String, PendingTurn>();
    private final Map<String, Long> turnRequestsSent = new ConcurrentHashMap<String, Long>();
    private final Map<String, Long> threadRecoveryAttempts = new ConcurrentHashMap<String, Long>();
    private final Map<String, QueuedTurn> activeQueuedTurns = new ConcurrentHashMap<String, QueuedTurn>();
    private final Map<String, Long> lastProgress = new ConcurrentHashMap<String, Long>();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<String, Object>();
    private final Map<String, HistoryCacheEntry> historyCache = new ConcurrentHashMap<String, HistoryCacheEntry>();
    private final ExecutorService notificationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "codex-event-handler");
        thread.setDaemon(true);
        return thread;
    });

    private static final class PendingTurn {
        private final long generation;
        private final String text;
        private final java.util.List<String> attachments;
        private final QueuedTurn queuedTurn;

        private PendingTurn(long generation, String text, java.util.List<String> attachments, QueuedTurn queuedTurn) {
            this.generation = generation;
            this.text = text;
            this.attachments = attachments;
            this.queuedTurn = queuedTurn;
        }
    }

    private static final class HistoryCacheEntry {
        private final String version;
        private final SessionHistory history;

        private HistoryCacheEntry(String version, SessionHistory history) {
            this.version = version;
            this.history = history;
        }
    }

    public CodexSessionService(SessionStore sessions, ProjectStore projects, CodexProcessManager processManager, SseHub hub, WorkspaceGuard guard, CodexWebProperties properties, AppSettingsStore appSettings) {
        this.sessions = sessions; this.projects = projects; this.processManager = processManager; this.hub = hub; this.guard = guard; this.properties = properties; this.appSettings = appSettings;
    }

    @PostConstruct
    public synchronized void recoverInterruptedSessions() {
        for (Session session : sessions.all()) {
            if (session.codexThreadId != null) threadSessions.put(session.codexThreadId, session.id);
            if (!"RUNNING".equals(session.status) && !"WAITING_APPROVAL".equals(session.status)) continue;
            session.status = "FAILED";
            session.codexThreadId = null;
            session.currentTurnId = null;
            session.steeringAvailable = false;
            session.cancelRequested = false;
            session.cancelledTurnId = null;
            sessions.save(session);
            append(session, "error", map("message", "Codex runtime 已重启，上一回合已中断"));
            if (hasQueuedTurns(session)) scheduleNextQueuedTurn(session);
        }
    }

    @PreDestroy
    public void shutdown() {
        notificationExecutor.shutdownNow();
    }

    public List<StoredEvent> events(String sessionId) { return events(sessionId, null); }
    public List<StoredEvent> events(String sessionId, String afterEventId) { requireSession(sessionId); return afterEventId == null ? sessions.events(sessionId) : sessions.eventsAfter(sessionId, afterEventId); }

    public SessionHistory history(String sessionId) {
        requireSession(sessionId);
        String version = sessions.eventsVersion(sessionId);
        HistoryCacheEntry cached = historyCache.get(sessionId);
        if (cached != null && cached.version.equals(version)) return cached.history;
        List<StoredEvent> source = sessions.events(sessionId);
        SessionHistory result = new SessionHistory();
        result.sourceEventCount = source.size();
        if (!source.isEmpty()) result.lastEventId = source.get(source.size() - 1).id;
        result.events = compactHistory(source);
        if (version.equals(sessions.eventsVersion(sessionId))) historyCache.put(sessionId, new HistoryCacheEntry(version, result));
        log.info("会话展示历史聚合: sessionId={}, sourceEvents={}, displayEvents={}", sessionId, source.size(), result.events.size());
        return result;
    }

    private List<StoredEvent> compactHistory(List<StoredEvent> source) {
        List<StoredEvent> result = new java.util.ArrayList<StoredEvent>();
        Map<String, StoredEvent> compacted = new LinkedHashMap<String, StoredEvent>();
        StoredEvent latestDiff = null;
        for (StoredEvent event : source) {
            if (event == null || event.type == null) continue;
            if ("agent.message.delta".equals(event.type)) {
                String itemId = eventValue(event, "itemId");
                String phase = eventValue(event, "phase");
                String turnId = eventValue(event, "turnId");
                String key = "message:" + (itemId == null ? (turnId == null ? "unknown" : turnId) : itemId) + ":" + (phase == null ? "commentary" : phase);
                StoredEvent target = compacted.get(key);
                if (target == null) {
                    target = compactEvent(event);
                    target.data = new LinkedHashMap<String, Object>();
                    copyValue(event.data, target.data, "itemId");
                    copyValue(event.data, target.data, "phase");
                    target.data.put("text", "");
                    compacted.put(key, target);
                    result.add(target);
                }
                appendText(target, eventValue(event, "text"));
                target.id = event.id;
                target.timestamp = event.timestamp;
                continue;
            }
            if ("tool.call.started".equals(event.type) || "tool.call.output".equals(event.type) || "tool.call.completed".equals(event.type)) {
                Map<String, Object> eventPayload = mapValue(event.data, "payload");
                Map<String, Object> eventItem = eventPayload == null ? null : mapValue(eventPayload, "item");
                if (eventItem == null || !"agentMessage".equals(String.valueOf(eventItem.get("type")))) continue;
                String rawId = eventValue(event, "itemId");
                if (rawId == null) rawId = eventValue(event, "callId");
                String key = "tool:" + event.type + ":" + (rawId == null ? event.id : rawId);
                StoredEvent target = compacted.get(key);
                if (target == null) {
                    target = compactEvent(event);
                    target.data = compactToolData(event);
                    compacted.put(key, target);
                    result.add(target);
                } else {
                    target.id = event.id;
                    target.timestamp = event.timestamp;
                    target.data = compactToolData(event);
                }
                continue;
            }
            if ("diff.updated".equals(event.type)) {
                if (latestDiff == null) {
                    latestDiff = compactEvent(event);
                    latestDiff.data = compactDiffData(event);
                    result.add(latestDiff);
                } else {
                    latestDiff.id = event.id;
                    latestDiff.timestamp = event.timestamp;
                    latestDiff.data = compactDiffData(event);
                }
                continue;
            }
            result.add(compactEvent(event));
        }
        return result;
    }

    private StoredEvent compactEvent(StoredEvent source) {
        StoredEvent result = new StoredEvent();
        result.id = source.id;
        result.type = source.type;
        result.sessionId = source.sessionId;
        result.timestamp = source.timestamp;
        result.data = source.data == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source.data);
        return result;
    }

    private Map<String, Object> compactToolData(StoredEvent event) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        copyValue(event.data, data, "text");
        copyValue(event.data, data, "itemId");
        copyValue(event.data, data, "phase");
        Map<String, Object> payload = mapValue(event.data, "payload");
        if (payload != null) {
            Map<String, Object> compactPayload = new LinkedHashMap<String, Object>();
            copyValue(payload, compactPayload, "itemId");
            copyValue(payload, compactPayload, "callId");
            Map<String, Object> item = mapValue(payload, "item");
            if (item != null) {
                Map<String, Object> compactItem = new LinkedHashMap<String, Object>();
                copyValue(item, compactItem, "id");
                copyValue(item, compactItem, "type");
                copyValue(item, compactItem, "phase");
                compactPayload.put("item", compactItem);
            }
            data.put("payload", compactPayload);
        }
        return data;
    }

    private Map<String, Object> compactDiffData(StoredEvent event) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        Map<String, Object> payload = mapValue(event.data, "payload");
        if (payload != null) {
            Map<String, Object> compactPayload = new LinkedHashMap<String, Object>();
            copyValue(payload, compactPayload, "diff");
            data.put("payload", compactPayload);
        }
        copyValue(event.data, data, "text");
        return data;
    }

    private void appendText(StoredEvent event, String text) {
        if (text == null || text.isEmpty()) return;
        String current = event.data == null ? "" : String.valueOf(event.data.get("text"));
        event.data.put("text", current + text);
    }

    private String eventValue(StoredEvent event, String key) {
        if (event == null || event.data == null) return null;
        Object direct = event.data.get(key);
        if (direct != null) return String.valueOf(direct);
        Map<String, Object> payload = mapValue(event.data, "payload");
        if (payload == null) return null;
        Object nested = payload.get(key);
        if (nested != null) return String.valueOf(nested);
        Map<String, Object> item = mapValue(payload, "item");
        Object itemValue = item == null ? null : item.get(key);
        return itemValue == null ? null : String.valueOf(itemValue);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> source, String key) {
        if (source == null) return null;
        Object value = source.get(key);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private void copyValue(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) target.put(key, source.get(key));
    }

    public synchronized void startTurn(String sessionId, String text) {
        startTurn(sessionId, text, java.util.Collections.<String>emptyList());
    }

    public synchronized void startTurn(String sessionId, String text, java.util.List<String> attachments) {
        if (text == null || text.trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_REQUIRED", "请输入任务内容");
        Session session = requireSession(sessionId);
        if ("RUNNING".equals(session.status) || "WAITING_APPROVAL".equals(session.status) || hasQueuedTurns(session)) {
            queueTurn(session, text, attachments);
            if (isTerminal(session.status)) scheduleNextQueuedTurn(session);
            return;
        }
        threadRecoveryAttempts.remove(session.id);
        startTurnNow(session, text, attachments, null);
    }

    private void startTurnNow(Session session, String text, java.util.List<String> attachments, QueuedTurn queuedTurn) {
        Project project = projects.find(session.projectId);
        if (project == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        guard.requireDirectory(project.path);
        CodexProtocolClient client = processManager.ensureStarted(this);
        java.util.List<String> safeAttachments = safeAttachments(session, attachments);
        boolean needsThread = session.codexThreadId == null;
        long generation = session.turnGeneration + 1;
        session.turnGeneration = generation;
        session.currentTurnId = null;
        session.lastUserMessage = text;
        if ("新建会话".equals(session.title)) session.title = summarizeTitle(text);
        session.status = "RUNNING";
        session.steeringAvailable = !needsThread;
        lastProgress.put(session.id, System.currentTimeMillis());
        if (needsThread) {
            pendingThreadStarts.put(session.id, generation);
            pendingTurns.put(session.id, new PendingTurn(generation, text, safeAttachments, queuedTurn));
        }
        if (queuedTurn != null) activeQueuedTurns.put(session.id, queuedTurn);
        log.info("回合启动: sessionId={}, threadId={}, generation={}, queued={}, needsThread={}", session.id, session.codexThreadId, generation, queuedTurn != null, needsThread);
        sessions.save(session);
        append(session, "turn.started", map("text", text));
        try {
            if (needsThread) {
                AppSettings settings = appSettings.get();
                client.startThreadAsync(project.path, settings.approvalPolicy, settings.model)
                        .whenComplete((response, failure) -> finishThreadStart(session.id, generation, text, safeAttachments, queuedTurn, response, failure));
            } else {
                client.startTurnAsync(session.codexThreadId, text, safeAttachments, appSettings.get().reasoningEffort)
                        .whenComplete((response, failure) -> finishTurnRequest(session.id, generation, queuedTurn, safeAttachments, response, failure));
            }
        } catch (RuntimeException exception) {
            failStart(session, exception.getMessage(), queuedTurn);
            throw exception;
        }
    }

    private void finishThreadStart(String sessionId, long generation, String text, java.util.List<String> attachments, QueuedTurn queuedTurn, JsonNode response, Throwable failure) {
        synchronized (this) {
            Session session = sessions.find(sessionId);
            if (session == null || session.turnGeneration != generation || !"RUNNING".equals(session.status) || session.cancelRequested) return;
            if (failure != null) {
                if (isTimeout(failure) && session.codexThreadId != null) {
                    log.warn("thread/start 响应超时但已收到线程事件，继续等待回合: sessionId={}, threadId={}, generation={}", sessionId, session.codexThreadId, generation);
                    return;
                }
                pendingThreadStarts.remove(sessionId);
                failStart(session, failure.getMessage(), queuedTurn);
                return;
            }
            JsonNode thread = response == null ? null : response.get("thread");
            String threadId = thread == null ? null : text(thread.get("id"));
            if (threadId == null) {
                failStart(session, "Codex 未返回 thread id", queuedTurn);
                return;
            }
            session.codexThreadId = threadId;
            threadSessions.put(threadId, session.id);
            pendingThreadStarts.remove(sessionId);
            pendingTurns.remove(sessionId);
            session.steeringAvailable = true;
            sessions.save(session);
            if (session.currentTurnId != null || Long.valueOf(generation).equals(turnRequestsSent.get(sessionId))) {
                log.info("thread/start 响应晚于 turn/started，跳过重复 turn/start: sessionId={}, threadId={}, turnId={}", sessionId, threadId, session.currentTurnId);
                return;
            }
            sendTurn(session, generation, text, attachments, queuedTurn);
        }
    }

    private void dispatchPendingTurn(Session session) {
        PendingTurn pending = pendingTurns.remove(session.id);
        if (pending == null || pending.generation != session.turnGeneration || session.currentTurnId != null) return;
        pendingThreadStarts.remove(session.id);
        log.info("根据 thread/started 通知发送待处理回合: sessionId={}, threadId={}, generation={}", session.id, session.codexThreadId, pending.generation);
        sendTurn(session, pending.generation, pending.text, pending.attachments, pending.queuedTurn);
    }

    private void sendTurn(Session session, long generation, String text, java.util.List<String> attachments, QueuedTurn queuedTurn) {
        try {
            CodexProtocolClient client = processManager.client();
            if (client == null) throw new IllegalStateException("Codex 运行时已停止");
            turnRequestsSent.put(session.id, generation);
            client.startTurnAsync(session.codexThreadId, text, attachments, appSettings.get().reasoningEffort)
                    .whenComplete((result, failure) -> finishTurnRequest(session.id, generation, queuedTurn, attachments, result, failure));
        } catch (RuntimeException exception) {
            failStart(session, exception.getMessage(), queuedTurn);
        }
    }

    private void finishTurnRequest(String sessionId, long generation, QueuedTurn queuedTurn, java.util.List<String> attachments, JsonNode response, Throwable failure) {
        synchronized (this) {
            Session session = sessions.find(sessionId);
            if (session == null || session.turnGeneration != generation || session.cancelRequested) return;
            if (!"RUNNING".equals(session.status)) return;
            if (failure == null) {
                String turnId = response == null ? null : text(response.path("turn").path("id"));
                if (turnId != null && session.currentTurnId == null) {
                    session.currentTurnId = turnId;
                    session.steeringAvailable = true;
                    sessions.save(session);
                    append(session, "turn.accepted", map("turnId", turnId));
                    log.info("回合已被 Codex 接受: sessionId={}, threadId={}, turnId={}", sessionId, session.codexThreadId, turnId);
                }
                return;
            }
            if (isTimeout(failure)) {
                log.warn("turn/start 响应超时，继续依赖通知事件判断回合状态: sessionId={}, threadId={}, turnId={}", sessionId, session.codexThreadId, session.currentTurnId);
                return;
            }
            if (isMissingThread(failure) && threadRecoveryAttempts.putIfAbsent(sessionId, generation) == null) {
                String staleThreadId = session.codexThreadId;
                log.warn("Codex 线程未加载，尝试恢复原线程上下文: sessionId={}, threadId={}, generation={}", sessionId, staleThreadId, generation);
                resumeOrStartNewThread(session, generation, queuedTurn, attachments, staleThreadId);
                return;
            }
            log.error("turn/start 失败: sessionId={}, threadId={}, turnId={}", sessionId, session.codexThreadId, session.currentTurnId, failure);
            failStart(session, failure.getMessage(), queuedTurn);
        }
    }

    private void resumeOrStartNewThread(Session session, long generation, QueuedTurn queuedTurn, java.util.List<String> attachments, String threadId) {
        if (threadId == null || processManager.client() == null) {
            startNewThread(session, generation, queuedTurn, attachments, threadId, null);
            return;
        }
        processManager.client().resumeThreadAsync(threadId).whenComplete((response, failure) -> {
            synchronized (this) {
                Session latest = sessions.find(session.id);
                if (latest == null || latest.turnGeneration != generation || !"RUNNING".equals(latest.status) || latest.cancelRequested) return;
                boolean resumed = failure == null && response != null && response.get("thread") != null;
                if (resumed) {
                    latest.codexThreadId = threadId;
                    latest.steeringAvailable = true;
                    threadSessions.put(threadId, latest.id);
                    sessions.save(latest);
                    log.info("Codex 线程已恢复，保留原上下文: sessionId={}, threadId={}, generation={}", latest.id, threadId, generation);
                    sendTurn(latest, generation, latest.lastUserMessage, attachments, queuedTurn);
                    return;
                }
                startNewThread(latest, generation, queuedTurn, attachments, threadId, failure);
            }
        });
    }

    private void startNewThread(Session session, long generation, QueuedTurn queuedTurn, java.util.List<String> attachments, String staleThreadId, Throwable resumeFailure) {
        if (staleThreadId != null) threadSessions.remove(staleThreadId, session.id);
        session.codexThreadId = null;
        session.currentTurnId = null;
        session.steeringAvailable = false;
        pendingThreadStarts.remove(session.id);
        pendingTurns.remove(session.id);
        turnRequestsSent.remove(session.id);
        sessions.save(session);
        if (resumeFailure != null) log.warn("Codex 线程恢复失败，降级创建新线程: sessionId={}, staleThreadId={}, message={}", session.id, staleThreadId, resumeFailure.getMessage());
        startTurnNow(session, session.lastUserMessage, attachments, queuedTurn);
    }

    private void failStart(Session session, String message, QueuedTurn queuedTurn) {
        if (queuedTurn != null) restoreQueuedTurn(session, queuedTurn);
        activeQueuedTurns.remove(session.id);
        pendingThreadStarts.remove(session.id);
        pendingTurns.remove(session.id);
        session.status = "FAILED";
        session.currentTurnId = null;
        session.steeringAvailable = false;
        lastProgress.remove(session.id);
        turnRequestsSent.remove(session.id);
        sessions.save(session);
        log.error("回合启动失败: sessionId={}, threadId={}, generation={}, message={}", session.id, session.codexThreadId, session.turnGeneration, message);
        append(session, "error", map("message", message == null ? "Codex 回合启动失败" : message));
        if (queuedTurn != null) append(session, "turn.queue.error", map("text", queuedTurn.text));
        else if (hasQueuedTurns(session)) scheduleNextQueuedTurn(session);
    }

    private java.util.List<String> safeAttachments(Session session, java.util.List<String> attachments) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        if (attachments == null) return result;
        for (String attachment : attachments) {
            java.nio.file.Path candidate = java.nio.file.Paths.get(attachment).toAbsolutePath().normalize();
            java.nio.file.Path uploadRoot = sessionsUploadRoot(session.id);
            if (candidate.startsWith(uploadRoot) && java.nio.file.Files.isRegularFile(candidate)) result.add(candidate.toString());
        }
        return result;
    }

    private void queueTurn(Session session, String text, java.util.List<String> attachments) {
        if (session.queuedTurns == null) session.queuedTurns = new java.util.ArrayList<QueuedTurn>();
        QueuedTurn queued = new QueuedTurn();
        queued.id = UUID.randomUUID().toString();
        queued.text = text.trim();
        queued.createdAt = java.time.Instant.now().toString();
        if (attachments != null) queued.attachments.addAll(attachments);
        session.queuedTurns.add(queued);
        session.lastUserMessage = queued.text;
        sessions.save(session);
        append(session, "turn.queued", map("text", queued.text));
    }

    public synchronized void cancel(String sessionId) {
        Session session = requireSession(sessionId);
        if (!"RUNNING".equals(session.status) && !"WAITING_APPROVAL".equals(session.status)) return;
        String cancelledTurnId = session.currentTurnId;
        CodexProtocolClient client = processManager.client();
        boolean interruptPending = false;
        if (session.codexThreadId != null && cancelledTurnId != null && client != null) {
            interruptPending = true;
            try {
                client.interruptAsync(session.codexThreadId, cancelledTurnId)
                        .whenComplete((result, failure) -> finishCancel(session.id, failure));
            } catch (RuntimeException exception) {
                append(session, "error", map("message", "停止请求发送失败，但本地会话已停止"));
            }
        }
        session.turnGeneration++;
        session.cancelRequested = true;
        session.cancelledTurnId = cancelledTurnId;
        session.currentTurnId = null;
        session.status = "CANCELLED";
        session.steeringAvailable = false;
        QueuedTurn activeQueued = activeQueuedTurns.remove(session.id);
        if (activeQueued != null) restoreQueuedTurn(session, activeQueued);
        sessions.save(session);
        lastProgress.remove(session.id);
        turnRequestsSent.remove(session.id);
        log.info("取消回合: sessionId={}, threadId={}, turnId={}, interruptPending={}", session.id, session.codexThreadId, cancelledTurnId, interruptPending);
        append(session, "turn.cancelled", Collections.<String, Object>emptyMap());
        if (!interruptPending && hasQueuedTurns(session)) {
            session.cancelRequested = false;
            session.cancelledTurnId = null;
            sessions.save(session);
            scheduleNextQueuedTurn(session);
        }
    }

    private void finishCancel(String sessionId, Throwable failure) {
        synchronized (this) {
            Session session = sessions.find(sessionId);
            if (session == null || !"CANCELLED".equals(session.status)) return;
            if (failure != null) {
                append(session, "error", map("message", "停止请求未获 Codex 确认，请检查运行状态"));
                return;
            }
            session.cancelRequested = false;
            session.cancelledTurnId = null;
            sessions.save(session);
            if (hasQueuedTurns(session)) scheduleNextQueuedTurn(session);
        }
    }

    public synchronized void steer(String sessionId, String text, java.util.List<String> attachments) {
        if (text == null || text.trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_REQUIRED", "请输入引导内容");
        Session session = requireSession(sessionId);
        if (!"RUNNING".equals(session.status) || session.codexThreadId == null || session.currentTurnId == null) throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_RUNNING", "当前会话没有正在运行的任务");
        if (Boolean.FALSE.equals(session.steeringAvailable)) throw new ApiException(HttpStatus.CONFLICT, "STEER_UNAVAILABLE", "当前回合不支持引导，请使用发送将消息排队");
        CodexProtocolClient client = processManager.client();
        if (client == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CODEX_NOT_RUNNING", "Codex 尚未运行");
        java.util.List<String> safeAttachments = new java.util.ArrayList<String>();
        if (attachments != null) for (String attachment : attachments) {
            java.nio.file.Path candidate = java.nio.file.Paths.get(attachment).toAbsolutePath().normalize();
            java.nio.file.Path uploadRoot = sessionsUploadRoot(session.id);
            if (candidate.startsWith(uploadRoot) && java.nio.file.Files.isRegularFile(candidate)) safeAttachments.add(candidate.toString());
        }
        try {
            final String turnId = session.currentTurnId;
            final long generation = session.turnGeneration;
            client.steerTurnAsync(session.codexThreadId, session.currentTurnId, text, safeAttachments)
                    .whenComplete((result, failure) -> finishSteer(session.id, generation, turnId, text, failure));
        } catch (RuntimeException exception) {
            session.steeringAvailable = false;
            sessions.save(session);
            append(session, "turn.steer.unavailable", Collections.<String, Object>emptyMap());
            throw new ApiException(HttpStatus.CONFLICT, "STEER_UNAVAILABLE", "当前回合已结束或暂不支持引导，请改用发送，消息会进入队列");
        }
        session.lastUserMessage = text;
        sessions.save(session);
    }

    private void finishSteer(String sessionId, long generation, String turnId, String text, Throwable failure) {
        synchronized (this) {
            Session session = sessions.find(sessionId);
            if (session == null) return;
            if (session.turnGeneration != generation || !turnId.equals(session.currentTurnId)) return;
            if (failure != null) {
                session.steeringAvailable = false;
                sessions.save(session);
                append(session, "turn.steer.unavailable", Collections.<String, Object>emptyMap());
                return;
            }
            append(session, "turn.steered", map("text", text));
        }
    }

    public synchronized void steerQueued(String sessionId, String queueId) {
        Session session = requireSession(sessionId);
        if (!"RUNNING".equals(session.status) || session.codexThreadId == null || session.currentTurnId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "SESSION_NOT_RUNNING", "当前会话没有正在运行的任务");
        }
        CodexProtocolClient client = processManager.client();
        if (client == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CODEX_NOT_RUNNING", "Codex 尚未运行");
        QueuedTurn queued = findQueued(session, queueId);
        if (queued == null) throw new ApiException(HttpStatus.NOT_FOUND, "QUEUE_ITEM_NOT_FOUND", "待发送消息不存在");
        try {
            String expectedTurnId = session.currentTurnId;
            JsonNode response = client.steerTurn(session.codexThreadId, expectedTurnId, queued.text, safeAttachments(session, queued.attachments));
            String acceptedTurnId = response == null ? null : text(response.get("turnId"));
            if (!expectedTurnId.equals(acceptedTurnId)) {
                throw new IllegalStateException("Codex 未确认引导已接受");
            }
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "STEER_UNAVAILABLE", "Codex 未接受这条引导，请等待当前任务进入可引导状态后重试");
        }
        session.queuedTurns.remove(queued);
        session.lastUserMessage = queued.text;
        sessions.save(session);
        append(session, "turn.steered", map("text", queued.text));
    }

    public synchronized void deleteQueued(String sessionId, String queueId) {
        Session session = requireSession(sessionId);
        QueuedTurn queued = findQueued(session, queueId);
        if (queued == null) throw new ApiException(HttpStatus.NOT_FOUND, "QUEUE_ITEM_NOT_FOUND", "待发送消息不存在");
        session.queuedTurns.remove(queued);
        sessions.save(session);
        append(session, "turn.queue.removed", map("text", queued.text));
    }

    private void startNextQueuedTurn(Session session) {
        Session latest = sessions.find(session.id);
        if (latest != null) session = latest;
        if (!"COMPLETED".equals(session.status) && !"CANCELLED".equals(session.status) && !"FAILED".equals(session.status)) return;
        if (session.cancelRequested) return;
        if (!hasQueuedTurns(session)) return;
        QueuedTurn next = session.queuedTurns.get(0);
        try {
            startTurnNow(session, next.text, next.attachments, next);
            session.queuedTurns.remove(0);
            sessions.save(session);
        } catch (RuntimeException exception) {
            append(session, "turn.queue.error", map("text", next.text));
        }
    }

    private String summarizeTitle(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 24) return normalized.isEmpty() ? "新建会话" : normalized;
        return normalized.substring(0, 24) + "...";
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
        notificationExecutor.execute(() -> handleMessage(method, params, requestId));
    }

    private void handleMessage(String method, JsonNode params, Long requestId) {
        String normalized = normalize(method, params);
        String threadId = findThreadId(params);
        Session session = findByThread(threadId);
        if (session == null && ("thread.started".equals(normalized) || "turn.started".equals(normalized))) {
            session = findPendingThreadSession();
        }
        if (session == null) {
            if (normalized != null) log.warn("收到无法匹配会话的 Codex 事件: method={}, normalized={}, threadId={}, requestId={}", method, normalized, threadId, requestId);
            return;
        }
        synchronized (sessionLock(session.id)) {
            Session latest = sessions.find(session.id);
            if (latest == null) return;
            if (threadId != null && latest.codexThreadId == null) {
                latest.codexThreadId = threadId;
                threadSessions.put(threadId, latest.id);
                pendingThreadStarts.remove(latest.id);
                latest.steeringAvailable = true;
                sessions.save(latest);
                log.info("从通知恢复线程绑定: sessionId={}, threadId={}, method={}", latest.id, threadId, method);
            }
            if (normalized == null) {
                log.warn("收到无法识别的 Codex 事件: sessionId={}, threadId={}, method={}", latest.id, threadId, method);
                return;
            }
            if ("thread.started".equals(normalized)) dispatchPendingTurn(latest);
            if ("turn.started".equals(normalized)) {
                pendingTurns.remove(latest.id);
                pendingThreadStarts.remove(latest.id);
            }
            handleMatchedMessage(latest, method, params, requestId, normalized);
        }
    }

    private void handleMatchedMessage(Session session, String method, JsonNode params, Long requestId, String normalized) {
        lastProgress.put(session.id, System.currentTimeMillis());
        String eventTurnId = findText(params, "turnId");
        if (eventTurnId == null) {
            JsonNode eventTurn = params == null ? null : params.get("turn");
            eventTurnId = findText(eventTurn, "id");
        }
        if (session.cancelRequested) {
            if (!"turn.started".equals(normalized) || eventTurnId == null || eventTurnId.equals(session.cancelledTurnId)) return;
            session.cancelRequested = false;
            session.cancelledTurnId = null;
            sessions.save(session);
        }
        // Codex can emit retryable transport errors while it reconnects the same turn.
        // They are progress notifications, not terminal session failures.
        if (normalized.equals("error") && willRetry(params)) normalized = "turn.retrying";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("method", method); if (params != null) data.put("payload", mapper.convertValue(params, Map.class));
        if (requestId != null) data.put("requestId", requestId);
        String delta = findText(params, "delta"); if (delta == null) delta = findText(params, "text"); if (delta != null) data.put("text", delta);
        String itemId = findItemId(params); if (itemId != null) data.put("itemId", itemId);
        String phase = findText(params, "phase");
        if (phase == null) phase = findText(params == null ? null : params.get("item"), "phase");
        if (phase != null) data.put("phase", phase);
        if (normalized.equals("turn.started")) {
            String turnId = eventTurnId;
            if (turnId != null) session.currentTurnId = turnId;
            session.steeringAvailable = true;
            updateStatus(session, "RUNNING");
        }
        if (normalized.equals("approval.request")) updateStatus(session, "WAITING_APPROVAL");
        if (normalized.equals("turn.completed")) {
            session.currentTurnId = null;
            session.steeringAvailable = false;
            updateStatus(session, "COMPLETED");
            data.put("queuedTurnCount", session.queuedTurns == null ? 0 : session.queuedTurns.size());
            lastProgress.remove(session.id);
            turnRequestsSent.remove(session.id);
            activeQueuedTurns.remove(session.id);
            threadRecoveryAttempts.remove(session.id);
        }
        if (normalized.equals("error")) {
            session.currentTurnId = null;
            session.steeringAvailable = false;
            updateStatus(session, "FAILED");
            data.put("queuedTurnCount", session.queuedTurns == null ? 0 : session.queuedTurns.size());
            lastProgress.remove(session.id);
            turnRequestsSent.remove(session.id);
            QueuedTurn activeQueued = activeQueuedTurns.remove(session.id);
            threadRecoveryAttempts.remove(session.id);
            if (activeQueued != null) restoreQueuedTurn(session, activeQueued);
        }
        append(session, normalized, data);
        logEvent(normalized, session, method, eventTurnId, requestId);
        if (normalized.equals("turn.completed") || normalized.equals("error")) scheduleNextQueuedTurn(session);
    }

    @Override
    public void onClosed(String reason) {
        log.error("Codex app-server 连接关闭: reason={}", reason);
        for (Session session : sessions.all()) if ("RUNNING".equals(session.status) || "WAITING_APPROVAL".equals(session.status)) {
            if (session.codexThreadId != null) threadSessions.remove(session.codexThreadId, session.id);
            session.status = "FAILED"; session.codexThreadId = null; session.currentTurnId = null; session.steeringAvailable = false; session.cancelRequested = false; session.cancelledTurnId = null;
            pendingThreadStarts.remove(session.id); pendingTurns.remove(session.id); lastProgress.remove(session.id); turnRequestsSent.remove(session.id);
            activeQueuedTurns.remove(session.id);
            sessions.save(session); append(session, "error", map("message", reason));
            if (hasQueuedTurns(session)) scheduleNextQueuedTurn(session);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000L)
    public void recoverStalledSessions() {
        long now = System.currentTimeMillis();
        for (Session session : sessions.all()) {
            Long progress = lastProgress.get(session.id);
            if (!"RUNNING".equals(session.status) || progress == null || now - progress < STALL_TIMEOUT_MILLIS) continue;
            synchronized (sessionLock(session.id)) {
                Session latest = sessions.find(session.id);
                Long latestProgress = lastProgress.get(session.id);
                if (latest == null || !"RUNNING".equals(latest.status) || latestProgress == null || now - latestProgress < STALL_TIMEOUT_MILLIS) continue;
                long stalledFor = now - latestProgress;
                log.error("回合超过 watchdog 无进展，标记失败: sessionId={}, threadId={}, turnId={}, stalledForMs={}", latest.id, latest.codexThreadId, latest.currentTurnId, stalledFor);
                latest.status = "FAILED";
                latest.currentTurnId = null;
                latest.steeringAvailable = false;
                sessions.save(latest);
                append(latest, "error", map("message", "Codex 回合超过 15 分钟没有进展，已自动标记失败"));
                lastProgress.remove(latest.id);
                turnRequestsSent.remove(latest.id);
                QueuedTurn activeQueued = activeQueuedTurns.remove(latest.id);
                if (activeQueued != null) restoreQueuedTurn(latest, activeQueued);
                if (hasQueuedTurns(latest)) scheduleNextQueuedTurn(latest);
            }
        }
    }

    private String normalize(String method, JsonNode params) {
        String value = method.toLowerCase();
        if (value.contains("requestapproval") || value.contains("approvalrequest") || value.contains("elicitation")) return "approval.request";
        if (value.equals("thread/started") || value.contains("threadstarted")) return "thread.started";
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
        String sessionId = threadSessions.get(threadId);
        return sessionId == null ? null : sessions.find(sessionId);
    }

    private Session findPendingThreadSession() {
        Session candidate = null;
        for (String sessionId : pendingThreadStarts.keySet()) {
            Session session = sessions.find(sessionId);
            if (session == null || !"RUNNING".equals(session.status) || session.codexThreadId != null) continue;
            if (candidate != null) {
                log.warn("无法唯一匹配 thread/start 通知: pendingSessions={}, method will be ignored", pendingThreadStarts.size());
                return null;
            }
            candidate = session;
        }
        return candidate;
    }

    private Session requireSession(String id) {
        Session session = sessions.find(id); if (session == null) throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在"); return session;
    }
    private void updateStatus(Session session, String status) {
        if (!status.equals(session.status)) log.info("会话状态变化: sessionId={}, threadId={}, turnId={}, from={}, to={}", session.id, session.codexThreadId, session.currentTurnId, session.status, status);
        session.status = status; sessions.save(session);
    }
    private boolean hasQueuedTurns(Session session) { return session != null && session.queuedTurns != null && !session.queuedTurns.isEmpty(); }
    private void restoreQueuedTurn(Session session, QueuedTurn queued) {
        if (session.queuedTurns == null) session.queuedTurns = new java.util.ArrayList<QueuedTurn>();
        if (findQueued(session, queued.id) == null) {
            session.queuedTurns.add(0, queued);
            append(session, "turn.queued", map("text", queued.text));
        }
    }
    private boolean isTerminal(String status) { return "COMPLETED".equals(status) || "CANCELLED".equals(status) || "FAILED".equals(status); }
    private QueuedTurn findQueued(Session session, String queueId) {
        if (session.queuedTurns == null) return null;
        for (QueuedTurn queued : session.queuedTurns) if (queueId != null && queueId.equals(queued.id)) return queued;
        return null;
    }
    private void scheduleNextQueuedTurn(Session session) {
        Thread queuedThread = new Thread(() -> {
            synchronized (CodexSessionService.this) { startNextQueuedTurn(session); }
        }, "codex-queued-turn");
        queuedThread.setDaemon(true);
        queuedThread.start();
    }
    private void append(Session session, String type, Map<String, Object> data) {
        StoredEvent event = new StoredEvent(); event.id = UUID.randomUUID().toString(); event.type = type; event.sessionId = session.id; event.timestamp = java.time.Instant.now().toString(); event.data = data;
        sessions.appendEvent(event); Map<String, Object> message = new LinkedHashMap<String, Object>(); message.put("id", event.id); message.put("type", type); message.put("sessionId", session.id); message.put("timestamp", event.timestamp); message.put("data", data); hub.publish(session.id, message);
    }
    private void logEvent(String normalized, Session session, String method, String turnId, Long requestId) {
        if ("turn.completed".equals(normalized) || "error".equals(normalized) || "approval.request".equals(normalized) || "turn.retrying".equals(normalized)) {
            log.info("Codex 事件: sessionId={}, threadId={}, turnId={}, method={}, normalized={}, requestId={}", session.id, session.codexThreadId, turnId, method, normalized, requestId);
        }
    }
    private String findThreadId(JsonNode node) {
        String value = findText(node, "threadId");
        if (value == null) value = findText(node, "conversationId");
        if (value == null) value = findText(node == null ? null : node.get("thread"), "id");
        return value;
    }
    private Object sessionLock(String sessionId) { return sessionLocks.computeIfAbsent(sessionId, key -> new Object()); }
    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) return true;
            current = current.getCause();
        }
        return false;
    }
    private boolean isMissingThread(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("thread not found") || normalized.contains("thread_not_found")) return true;
            }
            current = current.getCause();
        }
        return false;
    }
    private String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private String findText(JsonNode node, String field) { return node == null || node.get(field) == null ? null : text(node.get(field)); }
    private String findItemId(JsonNode params) {
        String itemId = findText(params, "itemId");
        if (itemId != null) return itemId;
        JsonNode item = params == null ? null : params.get("item");
        return findText(item, "id");
    }
    private boolean willRetry(JsonNode params) { return params != null && params.path("willRetry").asBoolean(false); }
    private Map<String, Object> map(String key, Object value) { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key, value); return result; }
    private java.nio.file.Path sessionsUploadRoot(String sessionId) { return java.nio.file.Paths.get(properties.getDataDir()).toAbsolutePath().normalize().resolve("uploads").resolve(sessionId); }
}
