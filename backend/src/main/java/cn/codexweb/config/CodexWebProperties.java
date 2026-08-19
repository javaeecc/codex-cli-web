package cn.codexweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "codex-web")
public class CodexWebProperties {
    private String dataDir;
    private String codexCommand = "codex.cmd";
    private String authToken = "";
    private String loginUsername = "";
    private String loginPassword = "";
    private long maxUploadBytes = 10485760L;
    private int diffMaxBytes = 524288;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getCodexCommand() { return codexCommand; }
    public void setCodexCommand(String codexCommand) { this.codexCommand = codexCommand; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public String getLoginUsername() { return loginUsername; }
    public void setLoginUsername(String loginUsername) { this.loginUsername = loginUsername; }
    public String getLoginPassword() { return loginPassword; }
    public void setLoginPassword(String loginPassword) { this.loginPassword = loginPassword; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public int getDiffMaxBytes() { return diffMaxBytes; }
    public void setDiffMaxBytes(int diffMaxBytes) { this.diffMaxBytes = diffMaxBytes; }
}
