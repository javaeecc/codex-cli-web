package cn.codexweb.storage;

import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.model.Session;
import cn.codexweb.model.StoredEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SessionStore {
    private final JsonFileStore files;
    private final CodexWebProperties properties;
    private Path sessionsDir;

    public SessionStore(JsonFileStore files, CodexWebProperties properties) {
        this.files = files;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        sessionsDir = Paths.get(properties.getDataDir()).toAbsolutePath().normalize().resolve("sessions");
        try { Files.createDirectories(sessionsDir); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public Session create(String projectId, String title) {
        Session session = new Session();
        session.id = UUID.randomUUID().toString();
        session.projectId = projectId;
        session.title = title == null || title.trim().isEmpty() ? "新建会话" : title.trim();
        session.status = "CREATED";
        session.archived = false;
        session.createdAt = java.time.Instant.now().toString();
        session.updatedAt = session.createdAt;
        save(session);
        return session;
    }

    public Session find(String id) {
        return files.read(sessionPath(id), Session.class, null);
    }

    public List<Session> all() {
        try {
            if (!Files.exists(sessionsDir)) return new ArrayList<Session>();
            return Files.list(sessionsDir).filter(Files::isDirectory).map(path -> find(path.getFileName().toString()))
                    .filter(session -> session != null).sorted(Comparator.comparing(session -> session.updatedAt, Comparator.reverseOrder()))
                    .collect(Collectors.toList());
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public List<Session> byProject(String projectId) {
        return all().stream().filter(session -> projectId.equals(session.projectId)).collect(Collectors.toList());
    }

    public void save(Session session) {
        session.updatedAt = java.time.Instant.now().toString();
        files.write(sessionPath(session.id), session);
    }

    public void appendEvent(StoredEvent event) {
        try { files.appendLine(eventsPath(event.sessionId), files.mapper().writeValueAsString(event)); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public List<StoredEvent> events(String sessionId) {
        Path path = eventsPath(sessionId);
        if (!Files.exists(path)) return new ArrayList<StoredEvent>();
        try {
            List<StoredEvent> result = new ArrayList<StoredEvent>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) result.add(files.mapper().readValue(line, new TypeReference<StoredEvent>() { }));
            }
            return result;
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    public void delete(String id) {
        Path directory = sessionsDir.resolve(id).normalize();
        if (!directory.getParent().equals(sessionsDir)) throw new IllegalArgumentException("非法会话路径");
        try { if (Files.exists(directory)) Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException ignored) { } }); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private Path sessionPath(String id) { return sessionsDir.resolve(id).resolve("session.json"); }
    private Path eventsPath(String id) { return sessionsDir.resolve(id).resolve("events.jsonl"); }
}
