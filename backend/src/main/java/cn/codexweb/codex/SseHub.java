package cn.codexweb.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import javax.annotation.PreDestroy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SseHub {
    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    // A browser tab owns the stream for a session. Keeping old streams around
    // makes reconnects compete for the same event queue and can lose terminal events.
    private static final int MAX_SUBSCRIBERS_PER_SESSION = 1;
    private static final int MAX_TOTAL_SUBSCRIBERS = 64;
    private static final int MAX_PENDING_MESSAGES = 256;
    private static final int MAX_EVENT_BYTES = 512 * 1024;
    private final ConcurrentMap<String, Deque<SseEmitter>> streams = new ConcurrentHashMap<String, Deque<SseEmitter>>();
    private final ConcurrentMap<SseEmitter, EmitterState> emitterStates = new ConcurrentHashMap<SseEmitter, EmitterState>();
    private final AtomicLong senderIds = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger subscriberCount = new java.util.concurrent.atomic.AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final class EmitterState {
        private final SseEmitter emitter;
        private final Object lock = new Object();
        private final ThreadPoolExecutor sender;
        private volatile boolean closed;

        private EmitterState(SseEmitter emitter, ThreadFactory threadFactory) {
            this.emitter = emitter;
            this.sender = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(MAX_PENDING_MESSAGES), threadFactory,
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    public SseEmitter subscribe(final String sessionId) {
        final SseEmitter emitter = new SseEmitter(0L);
        final EmitterState state = new EmitterState(emitter, runnable -> {
            Thread thread = new Thread(runnable, "codex-sse-sender-" + senderIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        emitterStates.put(emitter, state);
        if (subscriberCount.incrementAndGet() > MAX_TOTAL_SUBSCRIBERS) {
            subscriberCount.decrementAndGet();
            emitterStates.remove(emitter);
            state.closed = true;
            state.sender.shutdownNow();
            try { emitter.completeWithError(new IllegalStateException("SSE subscriber limit reached")); } catch (Exception ignored) { }
            log.warn("SSE 全局连接数已达上限: maxSubscribers={}", MAX_TOTAL_SUBSCRIBERS);
            return emitter;
        }

        Deque<SseEmitter> sessionStreams = streams.get(sessionId);
        if (sessionStreams == null) {
            Deque<SseEmitter> created = new ArrayDeque<SseEmitter>();
            Deque<SseEmitter> existing = streams.putIfAbsent(sessionId, created);
            sessionStreams = existing == null ? created : existing;
        }
        ArrayList<SseEmitter> evicted = new ArrayList<SseEmitter>();
        synchronized (sessionStreams) {
            sessionStreams.addLast(emitter);
            while (sessionStreams.size() > MAX_SUBSCRIBERS_PER_SESSION) evicted.add(sessionStreams.removeFirst());
        }
        for (SseEmitter old : evicted) {
            log.warn("淘汰重复或过期 SSE 连接: sessionId={}, maxSubscribers={}", sessionId, MAX_SUBSCRIBERS_PER_SESSION);
            remove(sessionId, old);
            try { old.complete(); } catch (Exception ignored) { }
        }

        log.info("SSE 连接建立: sessionId={}, subscribers={}", sessionId, subscriberCount(sessionId));
        final Deque<SseEmitter> registered = sessionStreams;
        Runnable cleanup = () -> remove(sessionId, registered, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        enqueue(state, sessionId, readyEvent(sessionId));
        return emitter;
    }

    public void publish(String sessionId, Map<String, Object> event) {
        Deque<SseEmitter> sessionStreams = streams.get(sessionId);
        if (sessionStreams == null) return;
        SseEmitter[] emitters;
        synchronized (sessionStreams) { emitters = sessionStreams.toArray(new SseEmitter[sessionStreams.size()]); }
        for (SseEmitter emitter : emitters) {
            EmitterState state = emitterStates.get(emitter);
            if (state != null) enqueue(state, sessionId, event);
        }
    }

    @Scheduled(fixedDelay = 15000L)
    public void heartbeat() {
        for (Map.Entry<String, Deque<SseEmitter>> entry : streams.entrySet()) {
            SseEmitter[] emitters;
            synchronized (entry.getValue()) { emitters = entry.getValue().toArray(new SseEmitter[entry.getValue().size()]); }
            for (SseEmitter emitter : emitters) {
                EmitterState state = emitterStates.get(emitter);
                if (state != null) enqueue(state, entry.getKey(), null);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (SseEmitter emitter : new ArrayList<SseEmitter>(emitterStates.keySet())) {
            EmitterState state = emitterStates.get(emitter);
            remove(null, emitter);
            try { emitter.complete(); } catch (Exception ignored) { }
            if (state != null) state.sender.shutdownNow();
        }
        streams.clear();
    }

    private void enqueue(final EmitterState state, final String sessionId, final Map<String, Object> event) {
        if (event != null) {
            try {
                if (mapper.writeValueAsBytes(event).length > MAX_EVENT_BYTES) {
                    log.warn("SSE 事件过大，断开连接以保护服务: sessionId={}, maxBytes={}", sessionId, MAX_EVENT_BYTES);
                    remove(sessionId, state.emitter);
                    try { state.emitter.completeWithError(new IllegalStateException("SSE event too large")); } catch (Exception ignored) { }
                    return;
                }
            } catch (Exception exception) {
                log.warn("SSE 事件序列化失败，断开连接: sessionId={}", sessionId, exception);
                remove(sessionId, state.emitter);
                try { state.emitter.completeWithError(exception); } catch (Exception ignored) { }
                return;
            }
        }
        try {
            state.sender.execute(() -> {
                if (state.closed) return;
                try {
                    synchronized (state.lock) {
                        if (state.closed) return;
                        if (event == null) state.emitter.send(SseEmitter.event().comment("keepalive"));
                        else state.emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
                    }
                } catch (Exception exception) {
                    log.warn("SSE {}发送失败: sessionId={}, pending={}", event == null ? "心跳" : "事件", sessionId, state.sender.getQueue().size(), exception);
                    remove(sessionId, state.emitter);
                    try { state.emitter.completeWithError(exception); } catch (Exception ignored) { }
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("SSE 发送队列已满，断开连接并等待恢复: sessionId={}, pending={}", sessionId, state.sender.getQueue().size());
            remove(sessionId, state.emitter);
            try { state.emitter.completeWithError(exception); } catch (Exception ignored) { }
        }
    }

    private void remove(String sessionId, SseEmitter emitter) {
        if (sessionId == null) {
            for (Map.Entry<String, Deque<SseEmitter>> entry : streams.entrySet()) remove(entry.getKey(), entry.getValue(), emitter);
            return;
        }
        Deque<SseEmitter> sessionStreams = streams.get(sessionId);
        if (sessionStreams != null) remove(sessionId, sessionStreams, emitter);
    }

    private void remove(String sessionId, Deque<SseEmitter> sessionStreams, SseEmitter emitter) {
        synchronized (sessionStreams) { sessionStreams.remove(emitter); }
        if (sessionStreams.isEmpty()) streams.remove(sessionId, sessionStreams);
        EmitterState state = emitterStates.remove(emitter);
        if (state != null) {
            subscriberCount.decrementAndGet();
            state.closed = true;
            state.sender.shutdownNow();
            if (sessionId != null) log.info("SSE 连接清理: sessionId={}, subscribers={}", sessionId, subscriberCount(sessionId));
        }
    }

    private int subscriberCount(String sessionId) {
        Deque<SseEmitter> sessionStreams = streams.get(sessionId);
        if (sessionStreams == null) return 0;
        synchronized (sessionStreams) { return sessionStreams.size(); }
    }

    private Map<String, Object> readyEvent(String sessionId) {
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("type", "stream.ready");
        result.put("sessionId", sessionId);
        return result;
    }
}
