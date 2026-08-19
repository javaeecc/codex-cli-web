package cn.codexweb.api;

import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.web.AuthInterceptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final CodexWebProperties properties;

    public AuthController(CodexWebProperties properties) { this.properties = properties; }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (!authenticated(session)) throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "需要登录");
        return userResponse(session);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (!properties.getLoginUsername().equals(username) || !properties.getLoginPassword().equals(password)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthInterceptor.SESSION_AUTHENTICATED, true);
        session.setAttribute("CODEX_WEB_USERNAME", username);
        return userResponse(session);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", false);
        return result;
    }

    private boolean authenticated(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(AuthInterceptor.SESSION_AUTHENTICATED));
    }

    private Map<String, Object> userResponse(HttpSession session) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", true);
        result.put("username", session.getAttribute("CODEX_WEB_USERNAME"));
        return result;
    }
}
