package cn.codexweb.model;

import java.util.ArrayList;
import java.util.List;

public class Session {
    public String id;
    public String projectId;
    public String codexThreadId;
    public String currentTurnId;
    public String title;
    public String status;
    public boolean archived;
    public String lastUserMessage;
    public String createdAt;
    public String updatedAt;
    // Null preserves compatibility with sessions written before steering was introduced.
    public Boolean steeringAvailable;
    public List<QueuedTurn> queuedTurns = new ArrayList<QueuedTurn>();
    public long turnGeneration;
    public boolean cancelRequested;
    public String cancelledTurnId;
}
