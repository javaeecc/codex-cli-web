package cn.codexweb.workspace;

import cn.codexweb.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
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
        return path;
    }

    public List<Path> roots() {
        List<Path> roots = new ArrayList<Path>();
        Arrays.stream(File.listRoots()).map(File::toPath).map(Path::toAbsolutePath).map(Path::normalize).forEach(roots::add);
        return roots;
    }
}
