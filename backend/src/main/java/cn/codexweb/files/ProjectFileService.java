package cn.codexweb.files;

import cn.codexweb.api.ApiException;
import cn.codexweb.workspace.WorkspaceGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectFileService {
    private static final long MAX_PREVIEW_BYTES = 2L * 1024L * 1024L;
    private final WorkspaceGuard guard;
    public ProjectFileService(WorkspaceGuard guard) { this.guard = guard; }

    public List<Map<String, Object>> tree(String rawProject, String rawPath) {
        Path project = guard.requireDirectory(rawProject);
        Path current = rawPath == null || rawPath.trim().isEmpty() ? project : project.resolve(rawPath).normalize();
        requireInside(project, current);
        if (!Files.isDirectory(current)) throw new ApiException(HttpStatus.NOT_FOUND, "DIRECTORY_NOT_FOUND", "目录不存在");
        try {
            return Files.list(current).filter(path -> !path.getFileName().toString().equals(".git"))
                    .sorted(Comparator.comparing(path -> (!Files.isDirectory(path)) + ":" + path.getFileName().toString().toLowerCase()))
                    .map(path -> entry(project, path)).collect(Collectors.toList());
        } catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILES_READ_FAILED", "无法读取文件树"); }
    }

    public Map<String, Object> content(String rawProject, String rawFile) {
        Path project = guard.requireDirectory(rawProject);
        if (rawFile == null || rawFile.trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "请选择文件");
        Path file = project.resolve(rawFile).normalize();
        requireInside(project, file);
        if (!Files.isRegularFile(file)) throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在");
        try {
            long size = Files.size(file);
            if (size > MAX_PREVIEW_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_PREVIEW_TOO_LARGE", "文件超过 2 MB，不能预览");
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("path", project.relativize(file).toString().replace('\\', '/'));
            result.put("size", size);
            result.put("binary", false);
            result.put("content", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return result;
        } catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_FAILED", "无法读取文件"); }
    }

    private Map<String, Object> entry(Path project, Path path) {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("name", path.getFileName().toString());
        item.put("path", project.relativize(path).toString().replace('\\', '/'));
        boolean directory = Files.isDirectory(path);
        item.put("directory", directory);
        if (!directory) {
            try {
                long size = Files.size(path);
                item.put("size", size);
                item.put("viewable", size <= MAX_PREVIEW_BYTES);
            } catch (IOException ignored) { item.put("size", 0L); item.put("viewable", false); }
        }
        return item;
    }

    private void requireInside(Path project, Path path) {
        if (!path.startsWith(project)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_PATH", "文件路径不合法");
    }
}
