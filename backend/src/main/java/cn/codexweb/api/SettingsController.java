package cn.codexweb.api;

import cn.codexweb.model.AppSettings;
import cn.codexweb.storage.AppSettingsStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SettingsController {
    private final AppSettingsStore settings;

    public SettingsController(AppSettingsStore settings) { this.settings = settings; }

    @GetMapping("/api/settings")
    public AppSettings get() { return settings.get(); }

    @PutMapping("/api/settings")
    public AppSettings update(@RequestBody Map<String, Object> body) {
        String policy = body == null || body.get("approvalPolicy") == null
                ? null : String.valueOf(body.get("approvalPolicy"));
        try { return settings.update(policy); }
        catch (IllegalArgumentException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_SETTINGS", exception.getMessage());
        }
    }
}
