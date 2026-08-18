package cn.codexweb.api;

import cn.codexweb.codex.CodexProcessManager;
import cn.codexweb.codex.CodexSessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RuntimeController {
    private final CodexProcessManager process;
    private final CodexSessionService sessions;
    public RuntimeController(CodexProcessManager process, CodexSessionService sessions) { this.process = process; this.sessions = sessions; }
    @PostMapping("/api/runtime/start") public Map<String, Object> start() { process.start(sessions); return process.status(); }
    @PostMapping("/api/runtime/stop") public Map<String, Object> stop() { process.stop(); return process.status(); }
    @PostMapping("/api/runtime/restart") public Map<String, Object> restart() { process.stop(); process.start(sessions); return process.status(); }
}
