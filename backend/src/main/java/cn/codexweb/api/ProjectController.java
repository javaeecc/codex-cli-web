package cn.codexweb.api;

import cn.codexweb.git.GitService;
import cn.codexweb.model.Project;
import cn.codexweb.model.Session;
import cn.codexweb.storage.ProjectStore;
import cn.codexweb.storage.SessionStore;
import cn.codexweb.workspace.WorkspaceGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
public class ProjectController {
    private final ProjectStore projects; private final SessionStore sessions; private final WorkspaceGuard guard; private final GitService git;
    public ProjectController(ProjectStore projects, SessionStore sessions, WorkspaceGuard guard, GitService git) { this.projects = projects; this.sessions = sessions; this.guard = guard; this.git = git; }
    @GetMapping("/api/projects") public List<Project> all() { return projects.all(); }
    @GetMapping("/api/projects/{id}") public Project get(@PathVariable String id) { return require(id); }
    @GetMapping("/api/projects/{id}/sessions") public List<Session> projectSessions(@PathVariable String id) { require(id); return sessions.byProject(id); }
    @PostMapping("/api/projects") public Project create(@RequestBody Map<String, String> body) {
        Path path = guard.requireDirectory(body.get("path")); Project project = new Project(); project.name = body.get("name"); project.path = path.toString();
        if (project.name == null || project.name.trim().isEmpty()) project.name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        project.isGitRepository = Files.isDirectory(path.resolve(".git")); project.createdAt = java.time.Instant.now().toString(); project.updatedAt = project.createdAt; project.lastUsedAt = project.createdAt;
        if (project.isGitRepository) project.lastBranch = String.valueOf(git.status(project.path).get("branch")); return projects.save(project);
    }
    @PutMapping("/api/projects/{id}") public Project update(@PathVariable String id, @RequestBody Map<String, String> body) { Project project = require(id); if (body.get("name") != null) project.name = body.get("name").trim(); project.updatedAt = java.time.Instant.now().toString(); return projects.save(project); }
    @DeleteMapping("/api/projects/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { require(id); for (Session session : sessions.byProject(id)) { if ("RUNNING".equals(session.status) || "WAITING_APPROVAL".equals(session.status)) throw new ApiException(HttpStatus.CONFLICT, "PROJECT_BUSY", "项目有运行中的 Codex 任务或审批"); } for (Session session : sessions.byProject(id)) sessions.delete(session.id); projects.delete(id); }
    private Project require(String id) { Project result = projects.find(id); if (result == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在"); return result; }
}
