package cn.codexweb.workspace;

import cn.codexweb.api.ApiException;
import cn.codexweb.config.CodexWebProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Component
public class WorkspaceGuard {
    private final CodexWebProperties properties;
    public WorkspaceGuard(CodexWebProperties properties) { this.properties = properties; }

    public Path requireDirectory(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_REQUIRED", "请选择工作空间");
        Path path = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!isAllowed(path)) throw new ApiException(HttpStatus.FORBIDDEN, "PATH_NOT_ALLOWED", "该目录不在允许的工作空间范围内");
        if (!Files.isDirectory(path)) throw new ApiException(HttpStatus.NOT_FOUND, "DIRECTORY_NOT_FOUND", "工作空间目录不存在");
        return path;
    }

    public boolean isAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (String rootValue : properties.getAllowedRoots()) {
            if (rootValue == null || rootValue.trim().isEmpty()) continue;
            Path root = Paths.get(rootValue).toAbsolutePath().normalize();
            if (normalized.startsWith(root)) return true;
        }
        return false;
    }

    public List<Path> roots() {
        List<Path> roots = new ArrayList<Path>();
        for (String value : properties.getAllowedRoots()) {
            if (value != null && !value.trim().isEmpty()) roots.add(Paths.get(value).toAbsolutePath().normalize());
        }
        return roots;
    }
}
