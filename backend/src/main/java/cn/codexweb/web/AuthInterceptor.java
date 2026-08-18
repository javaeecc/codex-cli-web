package cn.codexweb.web;

import cn.codexweb.config.CodexWebProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final CodexWebProperties properties;
    public AuthInterceptor(CodexWebProperties properties) { this.properties = properties; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("/api/health".equals(request.getRequestURI()) || properties.getAuthToken() == null || properties.getAuthToken().trim().isEmpty()) return true;
        String token = request.getHeader("X-Codex-Token");
        if (token == null) { String auth = request.getHeader("Authorization"); if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7); }
        if (token == null) token = request.getParameter("token");
        if (!properties.getAuthToken().equals(token)) { response.sendError(HttpStatus.UNAUTHORIZED.value(), "需要有效的 Codex Web 访问令牌"); return false; }
        return true;
    }
}
