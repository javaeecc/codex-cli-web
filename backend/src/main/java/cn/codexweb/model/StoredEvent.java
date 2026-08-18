package cn.codexweb.model;

import java.util.Map;

public class StoredEvent {
    public String id;
    public String type;
    public String sessionId;
    public String timestamp;
    public Map<String, Object> data;
}
