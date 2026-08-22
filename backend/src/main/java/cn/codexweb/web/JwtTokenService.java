package cn.codexweb.web;

import cn.codexweb.config.CodexWebProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtTokenService {
    private final CodexWebProperties properties;

    public JwtTokenService(CodexWebProperties properties) { this.properties = properties; }

    public String create(String username) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + properties.getJwtTtlMillis()))
                .signWith(SignatureAlgorithm.HS256, properties.getJwtSecret())
                .compact();
    }

    public String username(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        try {
            Jws<Claims> parsed = Jwts.parser().setSigningKey(properties.getJwtSecret()).parseClaimsJws(token);
            return parsed.getBody().getSubject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String tokenFrom(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        return authorization.substring(7).trim();
    }
}
