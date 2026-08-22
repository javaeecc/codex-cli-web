package cn.codexweb.api;

import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.web.JwtTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final long LOGIN_WINDOW_MILLIS = 60_000L;
    private final CodexWebProperties properties;
    private final JwtTokenService tokens;
    private final ConcurrentHashMap<String, LoginWindow> loginWindows = new ConcurrentHashMap<String, LoginWindow>();

    private static final class LoginWindow {
        private int failures;
        private long windowStartedAt;
    }

    public AuthController(CodexWebProperties properties, JwtTokenService tokens) {
        this.properties = properties;
        this.tokens = tokens;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        String username = tokens.username(tokens.tokenFrom(request.getHeader("Authorization")));
        if (username == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Login required");
        return userResponse(username, null);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        String remote = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (isRateLimited(remote) || !constantTimeEquals(properties.getLoginUsername(), username) || !constantTimeEquals(properties.getLoginPassword(), password)) {
            recordFailure(remote);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password");
        }
        loginWindows.remove(remote);
        return userResponse(username, tokens.create(username));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", false);
        return result;
    }

    private Map<String, Object> userResponse(String username, String token) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", true);
        result.put("username", username);
        if (token != null) result.put("token", token);
        return result;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isRateLimited(String key) {
        LoginWindow window = loginWindows.get(key);
        if (window == null) return false;
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.windowStartedAt >= LOGIN_WINDOW_MILLIS) {
                loginWindows.remove(key, window);
                return false;
            }
            return window.failures >= MAX_LOGIN_FAILURES;
        }
    }

    private void recordFailure(String key) {
        LoginWindow window = loginWindows.computeIfAbsent(key, value -> new LoginWindow());
        synchronized (window) {
            long now = System.currentTimeMillis();
            if (now - window.windowStartedAt >= LOGIN_WINDOW_MILLIS) {
                window.windowStartedAt = now;
                window.failures = 0;
            }
            window.failures++;
        }
    }
}
