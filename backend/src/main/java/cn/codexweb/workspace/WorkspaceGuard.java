package cn.codexweb.workspace;

import cn.codexweb.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class WorkspaceGuard {
    public Path requireDirectory(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_REQUIRED", "请选择工作空间");
        Path path = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new ApiException(HttpStatus.NOT_FOUND, "DIRECTORY_NOT_FOUND", "工作空间目录不存在");
        try { return path.toRealPath(); }
        catch (java.io.IOException exception) { throw new ApiException(HttpStatus.NOT_FOUND, "DIRECTORY_NOT_FOUND", "工作空间目录不存在"); }
    }

    /** Rejects symlink components so a project path cannot escape its root. */
    public Path requireExistingInside(Path rawRoot, Path rawPath) {
        Path root = realPath(rawRoot);
        Path candidate = rawPath.toAbsolutePath().normalize();
        requireLexicallyInside(root, candidate);
        rejectSymlinkComponents(root, candidate);
        Path real = realPath(candidate);
        if (!real.startsWith(root)) throw invalidPath();
        return real;
    }

    /** Resolves a path that may not exist yet, such as a deleted git file. */
    public Path requireInside(Path rawRoot, Path rawPath) {
        Path root = realPath(rawRoot);
        Path candidate = rawPath.toAbsolutePath().normalize();
        requireLexicallyInside(root, candidate);
        rejectSymlinkComponents(root, candidate);
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !realPath(existing).startsWith(root)) throw invalidPath();
        return candidate;
    }

    private void requireLexicallyInside(Path root, Path candidate) {
        if (!candidate.startsWith(root)) throw invalidPath();
    }

    private void rejectSymlinkComponents(Path root, Path candidate) {
        Path current = root;
        for (Path part : root.relativize(candidate)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw invalidPath();
        }
    }

    private Path realPath(Path path) {
        try { return path.toAbsolutePath().normalize().toRealPath(); }
        catch (java.io.IOException exception) { throw invalidPath(); }
    }

    private ApiException invalidPath() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_PATH", "文件路径不合法");
    }

    public List<Path> roots() {
        List<Path> roots = new ArrayList<Path>();
        Arrays.stream(File.listRoots()).map(File::toPath).map(Path::toAbsolutePath).map(Path::normalize).forEach(roots::add);
        return roots;
    }
}
