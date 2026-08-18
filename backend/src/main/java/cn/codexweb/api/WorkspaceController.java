package cn.codexweb.api;

import cn.codexweb.workspace.WorkspaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class WorkspaceController {
    private final WorkspaceService workspaces;
    public WorkspaceController(WorkspaceService workspaces) { this.workspaces = workspaces; }
    @GetMapping("/api/workspaces/roots") public List<Map<String, Object>> roots() { return workspaces.roots(); }
    @GetMapping("/api/workspaces") public List<Map<String, Object>> list(@RequestParam String path) { return workspaces.list(path); }
    @PostMapping("/api/workspaces") public Map<String, Object> create(@RequestBody Map<String, String> body) { Path path = workspaces.create(body.get("parent"), body.get("name")); Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("path", path.toString()); result.put("created", true); return result; }
}
