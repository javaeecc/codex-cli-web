package cn.codexweb.api;

import cn.codexweb.codex.CodexProcessManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {
    private final CodexProcessManager process;
    public HealthController(CodexProcessManager process) { this.process = process; }
    @GetMapping("/api/health")
    public Map<String, Object> health() { Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("ok", true); result.put("service", "codex-web"); result.put("runtime", process.status()); return result; }
    @GetMapping("/api/runtime")
    public Map<String, Object> runtime() { return process.status(); }
}
