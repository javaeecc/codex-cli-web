package cn.codexweb.api;

import cn.codexweb.files.ProjectFileService;
import cn.codexweb.model.Project;
import cn.codexweb.storage.ProjectStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class FileController {
    private final ProjectStore projects; private final ProjectFileService files;
    public FileController(ProjectStore projects, ProjectFileService files) { this.projects = projects; this.files = files; }
    @GetMapping("/api/projects/{id}/files") public List<Map<String, Object>> tree(@PathVariable String id, @RequestParam(required = false) String path) { return files.tree(require(id).path, path); }
    @GetMapping("/api/projects/{id}/files/content") public Map<String, Object> content(@PathVariable String id, @RequestParam String path) { return files.content(require(id).path, path); }
    private Project require(String id) { Project project = projects.find(id); if (project == null) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"); return project; }
}
