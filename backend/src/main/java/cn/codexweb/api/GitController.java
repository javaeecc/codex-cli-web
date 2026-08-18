package cn.codexweb.api;

import cn.codexweb.git.GitService;
import cn.codexweb.model.Project;
import cn.codexweb.storage.ProjectStore;
import cn.codexweb.codex.CodexSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class GitController {
    private final ProjectStore projects; private final GitService git; private final CodexSessionService sessions;
    public GitController(ProjectStore projects, GitService git, CodexSessionService sessions) { this.projects = projects; this.git = git; this.sessions = sessions; }
    @GetMapping("/api/projects/{id}/git/status") public Map<String, Object> status(@PathVariable String id) { return git.status(require(id).path); }
    @GetMapping("/api/projects/{id}/git/branches") public List<String> branches(@PathVariable String id) { return git.branches(require(id).path); }
    @PostMapping("/api/projects/{id}/git/checkout") public Map<String, Object> checkout(@PathVariable String id, @RequestBody Map<String, String> body) { if (sessions.projectBusy(id)) throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "PROJECT_BUSY", "当前项目有运行中的 Codex 任务或审批"); return git.checkout(require(id).path, body.get("branch")); }
    @GetMapping("/api/projects/{id}/git/diff") public Map<String, Object> diff(@PathVariable String id, @RequestParam(required = false) String file) { Map<String, Object> result = new java.util.LinkedHashMap<String, Object>(); result.put("diff", git.diff(require(id).path, file)); return result; }
    private Project require(String id) { Project project = projects.find(id); if (project == null) throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"); return project; }
}
