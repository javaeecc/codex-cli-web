package cn.codexweb.git;

import cn.codexweb.api.ApiException;
import cn.codexweb.config.CodexWebProperties;
import cn.codexweb.workspace.WorkspaceGuard;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class GitService {
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]+") ;
    private final WorkspaceGuard guard;
    private final CodexWebProperties properties;
    public GitService(WorkspaceGuard guard, CodexWebProperties properties) { this.guard = guard; this.properties = properties; }

    public Map<String, Object> status(String rawPath) {
        Path path = guard.requireDirectory(rawPath);
        CommandResult result = run(path, "status", "--short", "--branch");
        String[] lines = result.stdout.split("\\r?\\n");
        List<Map<String, String>> files = new ArrayList<Map<String, String>>();
        String branch = "";
        for (String line : lines) {
            if (line.startsWith("## ")) { branch = line.substring(3); int upstream = branch.indexOf("..."); if (upstream > 0) branch = branch.substring(0, upstream); int gone = branch.indexOf(" ["); if (gone > 0) branch = branch.substring(0, gone); continue; }
            if (line.length() < 4) continue;
            Map<String, String> file = new LinkedHashMap<String, String>();
            file.put("code", line.substring(0, 2));
            file.put("path", line.substring(3));
            file.put("kind", kind(line.substring(0, 2)));
            files.add(file);
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("isGitRepository", Files.isDirectory(path.resolve(".git")));
        response.put("branch", branch);
        response.put("files", files);
        return response;
    }

    public List<String> branches(String rawPath) {
        Path path = guard.requireDirectory(rawPath);
        CommandResult result = run(path, "branch", "--all", "--format=%(refname:short)");
        List<String> branches = new ArrayList<String>();
        for (String line : result.stdout.split("\\r?\\n")) if (!line.trim().isEmpty()) branches.add(line.trim());
        return branches;
    }

    public Map<String, Object> checkout(String rawPath, String branch) {
        if (branch == null || !BRANCH.matcher(branch).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BRANCH", "分支名不合法");
        Path path = guard.requireDirectory(rawPath);
        List<String> localBranches = localBranches(path);
        if (localBranches.contains(branch)) {
            run(path, "switch", branch);
        } else if (branch.indexOf('/') > 0) {
            String localBranch = branch.substring(branch.indexOf('/') + 1);
            if (!BRANCH.matcher(localBranch).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BRANCH", "INVALID_BRANCH");
            if (localBranches.contains(localBranch)) {
                run(path, "switch", localBranch);
            } else {
                run(path, "switch", "--track", "-c", localBranch, branch);
            }
        } else {
            run(path, "switch", branch);
        }
        return status(rawPath);
    }

    private List<String> localBranches(Path path) {
        CommandResult result = run(path, "branch", "--format=%(refname:short)");
        List<String> branches = new ArrayList<String>();
        for (String line : result.stdout.split("\\r?\\n")) if (!line.trim().isEmpty()) branches.add(line.trim());
        return branches;
    }

    public String diff(String rawPath, String file) {
        Path path = guard.requireDirectory(rawPath);
        if (file == null || file.trim().isEmpty()) return limit(run(path, "diff", "--no-ext-diff", "--unified=999999").stdout);
        Path target = guard.requireInside(path, path.resolve(file));
        return limit(run(path, "diff", "--no-ext-diff", "--unified=999999", "--", path.relativize(target).toString()).stdout);
    }

    private String limit(String value) { return value.length() <= properties.getDiffMaxBytes() ? value : value.substring(0, properties.getDiffMaxBytes()) + "\n[Diff 已达到返回上限]"; }

    private CommandResult run(Path directory, String... args) {
        List<String> command = new ArrayList<String>();
        command.add("git"); command.addAll(java.util.Arrays.asList(args));
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            int exit = process.waitFor();
            if (exit != 0) throw new ApiException(HttpStatus.BAD_REQUEST, "GIT_COMMAND_FAILED", output.toString().trim());
            return new CommandResult(output.toString(), exit);
        } catch (IOException exception) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GIT_UNAVAILABLE", "本机 Git 不可用"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_INTERRUPTED", "Git 操作被中断"); }
    }

    private String kind(String code) {
        if (code.contains("?") || code.contains("A")) return "added";
        if (code.contains("D")) return "deleted";
        return "modified";
    }

    private static class CommandResult {
        final String stdout; final int exit;
        CommandResult(String stdout, int exit) { this.stdout = stdout; this.exit = exit; }
    }
}
