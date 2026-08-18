package cn.codexweb.codex;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseHub {
    private final Map<String, Set<SseEmitter>> streams = new ConcurrentHashMap<String, Set<SseEmitter>>();

    public SseEmitter subscribe(final String sessionId) {
        final SseEmitter emitter = new SseEmitter(0L);
        Set<SseEmitter> sessionStreams = streams.get(sessionId);
        if (sessionStreams == null) {
            Set<SseEmitter> created = ConcurrentHashMap.newKeySet();
            Set<SseEmitter> existing = streams.putIfAbsent(sessionId, created);
            sessionStreams = existing == null ? created : existing;
        }
        sessionStreams.add(emitter);
        final Set<SseEmitter> registered = sessionStreams;
        Runnable cleanup = () -> remove(sessionId, registered, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().data(map("type", "stream.ready", "sessionId", sessionId), MediaType.APPLICATION_JSON));
        } catch (Exception exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(String sessionId, Map<String, Object> event) {
        Set<SseEmitter> sessionStreams = streams.get(sessionId);
        if (sessionStreams == null) return;
        for (SseEmitter emitter : sessionStreams) {
            try {
                emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
            } catch (Exception exception) {
                remove(sessionId, sessionStreams, emitter);
                emitter.completeWithError(exception);
            }
        }
    }

    @Scheduled(fixedDelay = 15000L)
    public void heartbeat() {
        for (Map.Entry<String, Set<SseEmitter>> entry : streams.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception exception) {
                    remove(entry.getKey(), entry.getValue(), emitter);
                    emitter.completeWithError(exception);
                }
            }
        }
    }

    private void remove(String sessionId, Set<SseEmitter> sessionStreams, SseEmitter emitter) {
        sessionStreams.remove(emitter);
        if (sessionStreams.isEmpty()) streams.remove(sessionId, sessionStreams);
    }

    private Map<String, Object> map(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put(key1, value1);
        result.put(key2, value2);
        return result;
    }
}
