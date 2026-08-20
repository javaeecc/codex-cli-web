package cn.codexweb.model;

import java.util.ArrayList;
import java.util.List;

public class SessionHistory {
    public List<StoredEvent> events = new ArrayList<StoredEvent>();
    public String lastEventId;
    public int sourceEventCount;
}
