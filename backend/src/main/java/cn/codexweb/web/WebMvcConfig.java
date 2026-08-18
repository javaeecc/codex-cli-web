package cn.codexweb.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;
    public WebMvcConfig(AuthInterceptor auth) { this.auth = auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(auth).addPathPatterns("/api/**"); }
}
