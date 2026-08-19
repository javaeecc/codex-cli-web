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
        return settings;
    }

    public synchronized AppSettings update(String approvalPolicy) {
        if (!isSupported(approvalPolicy)) throw new IllegalArgumentException("不支持的审批策略");
        AppSettings settings = get();
        settings.approvalPolicy = approvalPolicy;
        files.write(settingsPath, settings);
        return settings;
    }

    private boolean isSupported(String value) {
        return "untrusted".equals(value) || "on-failure".equals(value)
                || "on-request".equals(value) || "never".equals(value);
    }
}
