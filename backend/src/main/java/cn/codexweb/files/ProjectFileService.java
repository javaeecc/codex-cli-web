package cn.codexweb.files;

import cn.codexweb.api.ApiException;
import cn.codexweb.workspace.WorkspaceGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
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
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("path", project.relativize(file).toString().replace('\\', '/'));
            result.put("size", size);
            if (size > 1024 * 1024 || isBinary(file)) { result.put("binary", true); result.put("content", ""); }
            else { result.put("binary", false); result.put("content", new String(Files.readAllBytes(file), Charset.defaultCharset())); }
            return result;
        } catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_FAILED", "无法读取文件"); }
    }

    private Map<String, Object> entry(Path project, Path path) {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("name", path.getFileName().toString());
        item.put("path", project.relativize(path).toString().replace('\\', '/'));
        item.put("directory", Files.isDirectory(path));
        try { item.put("size", Files.isDirectory(path) ? 0L : Files.size(path)); } catch (IOException ignored) { item.put("size", 0L); }
        return item;
    }

    private void requireInside(Path project, Path path) {
        if (!path.startsWith(project)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_PATH", "文件路径不合法");
    }

    private boolean isBinary(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        int length = Math.min(data.length, 8192);
        for (int i = 0; i < length; i++) if (data[i] == 0) return true;
        return false;
    }
}
