package cn.codexweb.web;

import cn.codexweb.config.CodexWebProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String SESSION_AUTHENTICATED = "CODEX_WEB_AUTHENTICATED";
    private final CodexWebProperties properties;

    public AuthInterceptor(CodexWebProperties properties) { this.properties = properties; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if ("/api/health".equals(path) || path.startsWith("/api/auth/")) return true;

        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_AUTHENTICATED))) return true;

        String token = request.getHeader("X-Codex-Token");
        if (token == null) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) token = authorization.substring(7);
        }
        if (token == null) token = request.getParameter("token");
        if (properties.getAuthToken() != null && !properties.getAuthToken().trim().isEmpty() && properties.getAuthToken().equals(token)) return true;

        response.sendError(HttpStatus.UNAUTHORIZED.value(), "需要登录后访问 Codex Web");
        return false;
    }
}
