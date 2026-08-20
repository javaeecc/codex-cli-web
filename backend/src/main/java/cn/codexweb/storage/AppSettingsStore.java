package cn.codexweb.storage;

import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.model.AppSettings;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class AppSettingsStore {
    private final JsonFileStore files;
    private final CodexWebProperties properties;
    private Path settingsPath;

    public AppSettingsStore(JsonFileStore files, CodexWebProperties properties) {
        this.files = files;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        settingsPath = Paths.get(properties.getDataDir()).toAbsolutePath().normalize().resolve("app-config.json");
    }

    public synchronized AppSettings get() {
        AppSettings settings = files.read(settingsPath, AppSettings.class, new AppSettings());
        if (!isSupported(settings.approvalPolicy)) settings.approvalPolicy = "on-request";
        if (!isSupportedReasoningEffort(settings.reasoningEffort)) settings.reasoningEffort = "";
        return settings;
    }

    public synchronized AppSettings update(String approvalPolicy, String model, String reasoningEffort) {
        if (!isSupported(approvalPolicy)) throw new IllegalArgumentException("不支持的审批策略");
        AppSettings settings = get();
        settings.approvalPolicy = approvalPolicy;
        settings.model = normalizeModel(model);
        settings.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
        files.write(settingsPath, settings);
        return settings;
    }

    private String normalizeModel(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeReasoningEffort(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String normalized = value.trim().toLowerCase();
        if (!isSupportedReasoningEffort(normalized)) {
            throw new IllegalArgumentException("不支持的推理级别");
        }
        return normalized;
    }

    private boolean isSupportedReasoningEffort(String value) {
        return "low".equals(value) || "medium".equals(value)
                || "high".equals(value) || "xhigh".equals(value);
    }

    private boolean isSupported(String value) {
        return "untrusted".equals(value) || "on-failure".equals(value)
                || "on-request".equals(value) || "never".equals(value);
    }
}
