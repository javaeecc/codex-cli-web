package cn.codexweb.workspace;

import cn.codexweb.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkspaceService {
    private final WorkspaceGuard guard;
    public WorkspaceService(WorkspaceGuard guard) { this.guard = guard; }

    public List<Map<String, Object>> roots() {
        return guard.roots().stream().map(this::entry).collect(Collectors.toList());
    }

    public List<Map<String, Object>> list(String rawPath) {
        Path directory = guard.requireDirectory(rawPath);
        try {
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            Files.list(directory).filter(Files::isDirectory).sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .forEach(path -> result.add(entry(path)));
            return result;
        } catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_READ_FAILED", "无法读取工作空间目录"); }
    }

    public Path create(String rawParent, String name) {
        Path parent = guard.requireDirectory(rawParent);
        if (name == null || !name.matches("[a-zA-Z0-9._-]+")) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIRECTORY_NAME", "目录名只允许字母、数字、点、下划线和短横线");
        Path target = parent.resolve(name).normalize();
        if (Files.exists(target)) throw new ApiException(HttpStatus.CONFLICT, "DIRECTORY_UNAVAILABLE", "目录已存在");
        try { Files.createDirectory(target); return target; }
        catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DIRECTORY_CREATE_FAILED", "无法创建目录"); }
    }

    private Map<String, Object> entry(Path path) {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("name", path.getFileName() == null ? path.toString() : path.getFileName().toString());
        item.put("path", path.toString());
        item.put("isDirectory", Files.isDirectory(path));
        item.put("isGitRepository", Files.isDirectory(path.resolve(".git")));
        return item;
    }
}
