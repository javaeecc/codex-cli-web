package cn.codexweb.api;

import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.model.Session;
import cn.codexweb.storage.SessionStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
public class UploadController {
    private final SessionStore sessions; private final CodexWebProperties properties;
    public UploadController(SessionStore sessions, CodexWebProperties properties) { this.sessions = sessions; this.properties = properties; }
    @PostMapping("/api/sessions/{id}/uploads") public Map<String, Object> upload(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        Session session = sessions.find(id); if (session == null) throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "会话不存在");
        if (file == null || file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "请选择文件");
        if (file.getSize() > properties.getMaxUploadBytes()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "上传文件不能超过 10 MB");
        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._-]", "_");
        Path directory = java.nio.file.Paths.get(properties.getDataDir()).toAbsolutePath().normalize().resolve("uploads").resolve(id);
        try { Files.createDirectories(directory); Path target = directory.resolve(UUID.randomUUID().toString() + "-" + original); file.transferTo(target.toFile()); Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("name", original); result.put("path", target.toString()); result.put("size", file.getSize()); return result; }
        catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED", "文件上传失败"); }
    }
}
