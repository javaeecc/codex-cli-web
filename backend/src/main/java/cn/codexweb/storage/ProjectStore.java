package cn.codexweb.storage;

import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.model.Project;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectStore {
    private final JsonFileStore files;
    private final CodexWebProperties properties;
    private Path dataDir;
    private Path projectsFile;
    private final List<Project> projects = new ArrayList<Project>();

    public ProjectStore(JsonFileStore files, CodexWebProperties properties) {
        this.files = files;
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void load() {
        dataDir = Paths.get(properties.getDataDir()).toAbsolutePath().normalize();
        projectsFile = dataDir.resolve("projects.json");
        projects.clear();
        projects.addAll(files.readList(projectsFile, Project.class));
    }

    public synchronized List<Project> all() { return new ArrayList<Project>(projects); }
    public synchronized Project find(String id) {
        for (Project project : projects) if (project.id.equals(id)) return project;
        return null;
    }
    public synchronized Project save(Project project) {
        if (project.id == null || project.id.trim().isEmpty()) project.id = UUID.randomUUID().toString();
        boolean replaced = false;
        for (int i = 0; i < projects.size(); i++) if (projects.get(i).id.equals(project.id)) { projects.set(i, project); replaced = true; break; }
        if (!replaced) projects.add(project);
        files.write(projectsFile, projects);
        return project;
    }
    public synchronized void delete(String id) {
        projects.removeIf(project -> project.id.equals(id));
        files.write(projectsFile, projects);
    }
    public Path dataDir() { return dataDir; }
}
