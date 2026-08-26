package cn.codexweb.api;

import cn.codexweb.model.Project;
import cn.codexweb.model.Session;
import cn.codexweb.storage.ProjectStore;
import cn.codexweb.storage.SessionStore;
import cn.codexweb.codex.CodexSessionService;
import cn.codexweb.files.SessionMediaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

@RestController
public class SessionController {
    private final SessionStore sessions; private final ProjectStore projects; private final CodexSessionService codex; private final cn.codexweb.codex.SseHub streams; private final SessionMediaService media;
    public SessionController(SessionStore sessions, ProjectStore projects, CodexSessionService codex, cn.codexweb.codex.SseHub streams, SessionMediaService media) { this.sessions = sessions; this.projects = projects; this.codex = codex; this.streams = streams; this.media = media; }
    @GetMapping("/api/sessions") public List<Session> all() { return sessions.all(); }
    @GetMapping("/api/sessions/{id}") public Session get(@PathVariable String id) { return require(id); }
    @GetMapping("/api/sessions/{id}/events") public List<?> events(@PathVariable String id, @RequestParam(value = "after", required = false) String after) { return codex.events(id, after); }
    @GetMapping("/api/sessions/{id}/history") public cn.codexweb.model.SessionHistory history(@PathVariable String id, @RequestParam(value = "before", defaultValue = "0") int before, @RequestParam(value = "limit", defaultValue = "0") int limit) { return codex.history(id, before, limit); }
    @GetMapping("/api/sessions/{id}/media/{mediaId}") public ResponseEntity<Resource> media(@PathVariable String id, @PathVariable String mediaId) {
        Session session = require(id);
        SessionMediaService.MediaResource result = media.open(session, mediaId);
        return ResponseEntity.ok().contentType(result.mediaType).header("Cache-Control", "no-store").body(result.resource);
    }
    @GetMapping(value = "/api/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@PathVariable String id, HttpServletResponse response) {
        require(id);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = streams.subscribe(id);
        try { response.flushBuffer(); } catch (java.io.IOException exception) { emitter.completeWithError(exception); }
        return emitter;
    }
    @PostMapping("/api/sessions/{id}/turns") public Session startTurn(@PathVariable String id, @RequestBody Map<String, Object> body) { codex.startTurn(id, body == null ? null : text(body.get("text")), strings(body == null ? null : body.get("attachments"))); return require(id); }
    @PostMapping("/api/sessions/{id}/steer") public Session steer(@PathVariable String id, @RequestBody Map<String, Object> body) { codex.steer(id, body == null ? null : text(body.get("text")), strings(body == null ? null : body.get("attachments"))); return require(id); }
    @PostMapping("/api/sessions/{id}/queue/{queueId}/steer") public Session steerQueued(@PathVariable String id, @PathVariable String queueId) { codex.steerQueued(id, queueId); return require(id); }
    @DeleteMapping("/api/sessions/{id}/queue/{queueId}") public Session deleteQueued(@PathVariable String id, @PathVariable String queueId) { codex.deleteQueued(id, queueId); return require(id); }
    @PostMapping("/api/sessions/{id}/cancel") public Session cancel(@PathVariable String id) { codex.cancel(id); return require(id); }
    @PostMapping("/api/sessions/{id}/approval") public Session approval(@PathVariable String id, @RequestBody Map<String, Object> body) { codex.approval(id, approvalRequestId(body), body == null ? null : text(body.get("decision"))); return require(id); }
    @GetMapping("/api/sessions/{id}/export") public Map<String, Object> export(@PathVariable String id) { Session session = require(id); Map<String, Object> result = new java.util.LinkedHashMap<String, Object>(); result.put("session", session); result.put("events", codex.events(id)); return result; }
    @PostMapping("/api/projects/{projectId}/sessions") public Session create(@PathVariable String projectId, @RequestBody(required = false) Map<String, String> body) { requireProject(projectId); return sessions.create(projectId, body == null ? null : body.get("title")); }
    @PutMapping("/api/sessions/{id}") public Session update(@PathVariable String id, @RequestBody Map<String, Object> body) { Session session = require(id); if (body.get("title") != null) session.title = String.valueOf(body.get("title")); sessions.save(session); return session; }
    @PostMapping("/api/sessions/{id}/archive") public Session archive(@PathVariable String id) { Session session = require(id); session.archived = true; session.status = "ARCHIVED"; sessions.save(session); return session; }
    @PostMapping("/api/sessions/{id}/unarchive") public Session unarchive(@PathVariable String id) { Session session = require(id); session.archived = false; session.status = "IDLE"; sessions.save(session); return session; }
    @DeleteMapping("/api/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { require(id); sessions.delete(id); }
    private String text(Object value) { return value == null ? null : String.valueOf(value); }
    private Long approvalRequestId(Map<String, Object> body) {
        if (body == null || body.get("requestId") == null) return null;
        try { return Long.valueOf(String.valueOf(body.get("requestId"))); }
        catch (NumberFormatException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "APPROVAL_REQUEST_ID_INVALID", "审批请求编号不合法"); }
    }
    private List<String> strings(Object value) { List<String> result = new java.util.ArrayList<String>(); if (value instanceof Iterable) for (Object item : (Iterable<?>) value) result.add(text(item)); return result; }
    private Session require(String id) { Session result = sessions.find(id); if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在"); return result; }
    private Project requireProject(String id) { Project result = projects.find(id); if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"); return result; }
}
