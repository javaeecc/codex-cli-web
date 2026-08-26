package cn.codexweb.files;

import cn.codexweb.api.ApiException;
import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.model.Session;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SessionMediaService {
    private static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    private final CodexWebProperties properties;

    public SessionMediaService(CodexWebProperties properties) { this.properties = properties; }

    public Map<String, Object> captureImage(Session session, Map<String, Object> payload) {
        ImageSource source = findImage(payload, false);
        if (source == null) return null;
        try {
            Path directory = mediaDirectory(session.id);
            Files.createDirectories(directory);
            String extension = extension(source.mimeType, source.name);
            String id = UUID.randomUUID().toString().replace("-", "") + extension;
            Path target = directory.resolve(id);
            if (source.dataUri != null) Files.write(target, decodeDataUri(source.dataUri));
            else Files.copy(source.path, target, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(target) > MAX_IMAGE_BYTES) {
                Files.deleteIfExists(target);
                return null;
            }
            Map<String, Object> media = new LinkedHashMap<String, Object>();
            media.put("id", id);
            media.put("mimeType", source.mimeType);
            return media;
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    public MediaResource open(Session session, String id) {
        if (id == null || !id.matches("[a-fA-F0-9-]+\\.(png|jpe?g|gif|webp|bmp)")) throw notFound();
        Path file = mediaDirectory(session.id).resolve(id).normalize();
        Path directory = mediaDirectory(session.id).toAbsolutePath().normalize();
        if (!file.toAbsolutePath().startsWith(directory) || !Files.isRegularFile(file)) throw notFound();
        try {
            String mime = Files.probeContentType(file);
            if (mime == null || !mime.startsWith("image/")) mime = mimeFromExtension(file.getFileName().toString());
            return new MediaResource(new FileSystemResource(file), MediaType.parseMediaType(mime));
        } catch (IOException | IllegalArgumentException exception) {
            throw notFound();
        }
    }

    private ImageSource findImage(Object value, boolean imageContext) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            String type = map.get("type") == null ? "" : String.valueOf(map.get("type")).toLowerCase();
            String method = map.get("method") == null ? "" : String.valueOf(map.get("method")).toLowerCase();
            boolean currentImageContext = imageContext || method.contains("image") || type.contains("image");
            for (String key : new String[] { "imageUrl", "image_url" }) {
                Object candidate = map.get(key);
                if (candidate instanceof String) {
                    ImageSource source = imageValue((String) candidate, key);
                    if (source != null) return source;
                }
            }
            for (String key : new String[] { "path", "savedPath", "imagePath" }) {
                Object candidate = map.get(key);
                if ((currentImageContext || key.equals("imagePath")) && candidate instanceof String) {
                    ImageSource source = fileValue((String) candidate);
                    if (source != null) return source;
                }
            }
            for (String key : new String[] { "payload", "item", "contentItems", "result", "output", "content" }) {
                ImageSource source = findImage(map.get(key), currentImageContext);
                if (source != null) return source;
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                ImageSource source = findImage(item, imageContext);
                if (source != null) return source;
            }
        }
        return null;
    }

    private ImageSource imageValue(String value, String name) {
        if (value.startsWith("data:image/")) {
            int separator = value.indexOf(';');
            if (separator < 0) return null;
            String mime = value.substring(5, separator);
            return new ImageSource(value, null, mime, name);
        }
        return fileValue(value);
    }

    private ImageSource fileValue(String value) {
        try {
            Path path = Paths.get(value).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !isImageName(path.getFileName().toString()) || Files.size(path) > MAX_IMAGE_BYTES) return null;
            String mime = Files.probeContentType(path);
            if (mime == null || !mime.startsWith("image/")) mime = mimeFromExtension(path.getFileName().toString());
            return isAllowedMime(mime) ? new ImageSource(null, path, mime, path.getFileName().toString()) : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private byte[] decodeDataUri(String value) throws IOException {
        int comma = value.indexOf(',');
        if (comma < 0) throw new IOException("invalid image data");
        byte[] bytes = Base64.getDecoder().decode(value.substring(comma + 1));
        if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("image too large");
        return bytes;
    }

    private Path mediaDirectory(String sessionId) {
        return Paths.get(properties.getDataDir()).toAbsolutePath().normalize().resolve("sessions").resolve(sessionId).resolve("media");
    }

    private boolean isImageName(String name) { return name != null && name.toLowerCase().matches(".*\\.(png|jpe?g|gif|webp|bmp)$"); }
    private boolean isAllowedMime(String mime) { return mime != null && (mime.equals("image/png") || mime.equals("image/jpeg") || mime.equals("image/gif") || mime.equals("image/webp") || mime.equals("image/bmp")); }
    private String mimeFromExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/bmp";
    }
    private String extension(String mime, String name) {
        if (mime != null && mime.equals("image/png")) return ".png";
        if (mime != null && mime.equals("image/gif")) return ".gif";
        if (mime != null && mime.equals("image/webp")) return ".webp";
        if (mime != null && mime.equals("image/bmp")) return ".bmp";
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".jpeg")) return ".jpeg";
            if (lower.endsWith(".jpg")) return ".jpg";
        }
        return ".jpg";
    }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "图片不存在"); }

    public static class MediaResource {
        public final Resource resource;
        public final MediaType mediaType;
        public MediaResource(Resource resource, MediaType mediaType) { this.resource = resource; this.mediaType = mediaType; }
    }
    private static class ImageSource {
        final String dataUri;
        final Path path;
        final String mimeType;
        final String name;
        ImageSource(String dataUri, Path path, String mimeType, String name) { this.dataUri = dataUri; this.path = path; this.mimeType = mimeType; this.name = name; }
    }
}
