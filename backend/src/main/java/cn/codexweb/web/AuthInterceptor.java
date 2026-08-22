package cn.codexweb.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtTokenService tokens;

    public AuthInterceptor(JwtTokenService tokens) { this.tokens = tokens; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if ("/api/health".equals(path) || path.startsWith("/api/auth/")) return true;

        String username = tokens.username(tokens.tokenFrom(request.getHeader("Authorization")));
        if (username != null && !username.isEmpty()) return true;

        response.setHeader("Cache-Control", "no-store");
        response.sendError(HttpStatus.UNAUTHORIZED.value(), "Login required");
        return false;
    }
}
