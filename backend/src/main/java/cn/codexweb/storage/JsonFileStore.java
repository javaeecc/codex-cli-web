package cn.codexweb.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class JsonFileStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<String, ReentrantLock>();

    public <T> T read(Path path, Class<T> type, T fallback) {
        if (!Files.exists(path)) return fallback;
        try {
            return mapper.readValue(path.toFile(), type);
        } catch (IOException exception) {
            Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
            try { Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            return fallback;
        }
    }

    public <T> List<T> readList(Path path, Class<T> type) {
        if (!Files.exists(path)) return Collections.emptyList();
        try {
            return mapper.readValue(path.toFile(), mapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (IOException exception) {
            Path backup = path.resolveSibling(path.getFileName().toString() + ".bak");
            try { Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            return Collections.emptyList();
        }
    }

    public void write(Path path, Object value) {
        ReentrantLock lock = locks.computeIfAbsent(path.toAbsolutePath().toString(), key -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Paths.get(path.toString() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            if (Files.exists(path)) Files.copy(path, path.resolveSibling(path.getFileName().toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存本地数据", exception);
        } finally {
            lock.unlock();
        }
    }

    public void appendLine(Path path, String line) {
        ReentrantLock lock = locks.computeIfAbsent(path.toAbsolutePath().toString(), key -> new ReentrantLock());
        lock.lock();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("无法追加会话事件", exception);
        } finally {
            lock.unlock();
        }
    }

    public ObjectMapper mapper() { return mapper; }
}
