package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Persistent project profiles and private per-session environment files. */
public final class TermuxWorkspaceManager {

    private static final String PREFERENCES_NAME = "termux_workspaces";
    private static final String KEY_WORKSPACES = "profiles_v1";
    private static final String KEY_SESSION_BINDINGS = "session_bindings_v1";
    private static final String ENVIRONMENT_DIRECTORY = TermuxConstants.TERMUX_HOME_DIR_PATH
        + "/.termux/workspace-environments";
    private static final int MAX_WORKSPACES = 32;
    private static final int MAX_ENVIRONMENT_VARIABLES = 64;
    private static final int MAX_ENVIRONMENT_TEXT_LENGTH = 8192;
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DIRECTORY_LENGTH = 1024;
    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static final String ERROR_NAME_REQUIRED = "NAME_REQUIRED";
    public static final String ERROR_NAME_TOO_LONG = "NAME_TOO_LONG";
    public static final String ERROR_DUPLICATE_NAME = "DUPLICATE_NAME";
    public static final String ERROR_DIRECTORY_INVALID = "DIRECTORY_INVALID";
    public static final String ERROR_ENVIRONMENT_INVALID = "ENVIRONMENT_INVALID";
    public static final String ERROR_ENVIRONMENT_TOO_LARGE = "ENVIRONMENT_TOO_LARGE";
    public static final String ERROR_TOO_MANY_WORKSPACES = "TOO_MANY_WORKSPACES";

    private final SharedPreferences preferences;
    private List<Workspace> cachedWorkspaces;
    private LinkedHashMap<String, SessionBinding> cachedBindings;

    public TermuxWorkspaceManager(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void invalidateCache() {
        cachedWorkspaces = null;
        cachedBindings = null;
    }

    public synchronized List<Workspace> getWorkspaces() {
        ensureLoaded();
        return Collections.unmodifiableList(new ArrayList<>(cachedWorkspaces));
    }

    public synchronized Workspace getWorkspace(String id) {
        if (id == null) return null;
        ensureLoaded();
        for (Workspace workspace : cachedWorkspaces) {
            if (id.equals(workspace.id)) return workspace;
        }
        return null;
    }

    public synchronized Workspace getWorkspaceForSession(String sessionHandle) {
        if (sessionHandle == null) return null;
        ensureLoaded();
        SessionBinding binding = cachedBindings.get(sessionHandle);
        return binding == null ? null : getWorkspace(binding.workspaceId);
    }

    public synchronized String getWorkspaceIdForSession(String sessionHandle) {
        if (sessionHandle == null) return null;
        ensureLoaded();
        SessionBinding binding = cachedBindings.get(sessionHandle);
        return binding == null ? null : binding.workspaceId;
    }

    public synchronized Workspace saveWorkspace(String id, String name, String workingDirectory,
                                                String environmentText) throws WorkspaceValidationException {
        ensureLoaded();
        String cleanedName = validateName(name);
        String cleanedDirectory = normalizeUbuntuDirectory(workingDirectory);
        LinkedHashMap<String, String> environment = parseEnvironmentText(environmentText);

        for (Workspace workspace : cachedWorkspaces) {
            if (!workspace.id.equals(id == null ? "" : id)
                && workspace.name.toLowerCase(Locale.ROOT).equals(cleanedName.toLowerCase(Locale.ROOT))) {
                throw new WorkspaceValidationException(ERROR_DUPLICATE_NAME);
            }
        }

        boolean exists = false;
        if (id != null) {
            for (Workspace workspace : cachedWorkspaces) {
                if (workspace.id.equals(id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) throw new IllegalArgumentException("Workspace does not exist: " + id);
        } else if (cachedWorkspaces.size() >= MAX_WORKSPACES) {
            throw new WorkspaceValidationException(ERROR_TOO_MANY_WORKSPACES);
        }

        Workspace saved = new Workspace(id == null ? UUID.randomUUID().toString() : id,
            cleanedName, cleanedDirectory, environment);
        if (exists) {
            for (int i = 0; i < cachedWorkspaces.size(); i++) {
                if (cachedWorkspaces.get(i).id.equals(saved.id)) {
                    cachedWorkspaces.set(i, saved);
                    break;
                }
            }
        } else {
            cachedWorkspaces.add(saved);
        }
        persistWorkspaces();
        return saved;
    }

    /** Delete only the profile; already-running sessions remain alive and become ungrouped. */
    public synchronized void deleteWorkspace(String id) {
        if (id == null) return;
        ensureLoaded();
        for (int i = cachedWorkspaces.size() - 1; i >= 0; i--) {
            if (id.equals(cachedWorkspaces.get(i).id)) cachedWorkspaces.remove(i);
        }
        for (SessionBinding binding : cachedBindings.values()) {
            if (id.equals(binding.workspaceId)) binding.workspaceId = null;
        }
        persistWorkspaces();
        persistBindings();
    }

    /** Create a private shell-quoted environment file and host variables for the Ubuntu login script. */
    public synchronized PreparedSession prepareSession(Workspace workspace) throws IOException {
        if (workspace == null || getWorkspace(workspace.id) == null)
            throw new IllegalArgumentException("A saved workspace is required.");
        String environmentFilePath = null;
        if (!workspace.environment.isEmpty()) environmentFilePath = writePrivateEnvironmentFile(workspace.environment);
        HashMap<String, String> environment = new HashMap<>();
        environment.put("TERMUX_UBUNTU_WORKDIR", workspace.workingDirectory);
        if (environmentFilePath != null) environment.put("TERMUX_UBUNTU_ENV_FILE", environmentFilePath);
        return new PreparedSession(workspace.id, environmentFilePath, environment);
    }

    public synchronized void attachSession(String sessionHandle, PreparedSession preparedSession) {
        if (sessionHandle == null || sessionHandle.isEmpty() || preparedSession == null)
            throw new IllegalArgumentException("Session handle and prepared workspace are required.");
        ensureLoaded();
        cachedBindings.put(sessionHandle,
            new SessionBinding(preparedSession.workspaceId, preparedSession.environmentFilePath));
        persistBindings();
    }

    public synchronized void discardPreparedSession(PreparedSession preparedSession) {
        if (preparedSession != null) deleteEnvironmentFile(preparedSession.environmentFilePath);
    }

    /** Remove a binding and its private environment file after the terminal session is actually removed. */
    public synchronized void removeSession(String sessionHandle) {
        if (sessionHandle == null) return;
        ensureLoaded();
        SessionBinding binding = cachedBindings.remove(sessionHandle);
        if (binding != null) deleteEnvironmentFile(binding.environmentFilePath);
        persistBindings();
    }

    /** Drop stale session assignments after the service reconnects and remove orphaned private files. */
    public synchronized void pruneSessions(Set<String> liveSessionHandles) {
        ensureLoaded();
        Set<String> live = liveSessionHandles == null ? Collections.emptySet() : liveSessionHandles;
        Iterator<Map.Entry<String, SessionBinding>> iterator = cachedBindings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SessionBinding> entry = iterator.next();
            if (!live.contains(entry.getKey())) {
                deleteEnvironmentFile(entry.getValue().environmentFilePath);
                iterator.remove();
            }
        }
        persistBindings();

        File directory = new File(ENVIRONMENT_DIRECTORY);
        File[] files = directory.listFiles();
        if (files != null) {
            Set<String> referenced = new java.util.HashSet<>();
            for (SessionBinding binding : cachedBindings.values()) {
                if (binding.environmentFilePath != null) referenced.add(binding.environmentFilePath);
            }
            for (File file : files) {
                boolean installerFile = file.getName().endsWith(".env") || file.getName().endsWith(".tmp");
                if (installerFile && file.isFile() && !referenced.contains(file.getAbsolutePath())) file.delete();
            }
        }
    }

    public static String normalizeUbuntuDirectory(String directory) throws WorkspaceValidationException {
        if (directory == null) throw new WorkspaceValidationException(ERROR_DIRECTORY_INVALID);
        String value = directory.trim();
        if (value.isEmpty() || value.length() > MAX_DIRECTORY_LENGTH || !value.startsWith("/")
            || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new WorkspaceValidationException(ERROR_DIRECTORY_INVALID);
        }
        ArrayList<String> segments = new ArrayList<>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) segments.remove(segments.size() - 1);
            } else {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) return "/";
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) normalized.append('/').append(segment);
        return normalized.toString();
    }

    public static LinkedHashMap<String, String> parseEnvironmentText(String text)
        throws WorkspaceValidationException {
        String value = text == null ? "" : text;
        if (value.length() > MAX_ENVIRONMENT_TEXT_LENGTH)
            throw new WorkspaceValidationException(ERROR_ENVIRONMENT_TOO_LARGE);
        LinkedHashMap<String, String> environment = new LinkedHashMap<>();
        String[] lines = value.split("\\n", -1);
        for (String rawLine : lines) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) throw new WorkspaceValidationException(ERROR_ENVIRONMENT_INVALID);
            String key = line.substring(0, separator).trim();
            String variableValue = line.substring(separator + 1);
            if (!ENVIRONMENT_KEY.matcher(key).matches() || variableValue.indexOf('\0') >= 0
                || variableValue.indexOf('\r') >= 0 || variableValue.indexOf('\n') >= 0
                || isReservedWorkspaceVariable(key) || environment.containsKey(key)) {
                throw new WorkspaceValidationException(ERROR_ENVIRONMENT_INVALID);
            }
            if (environment.size() >= MAX_ENVIRONMENT_VARIABLES)
                throw new WorkspaceValidationException(ERROR_ENVIRONMENT_TOO_LARGE);
            environment.put(key, variableValue);
        }
        return environment;
    }

    public static String formatEnvironmentText(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    /** POSIX single-quote escaping for a value in the private environment file. */
    static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    private static String validateName(String name) throws WorkspaceValidationException {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) throw new WorkspaceValidationException(ERROR_NAME_REQUIRED);
        if (value.length() > MAX_NAME_LENGTH) throw new WorkspaceValidationException(ERROR_NAME_TOO_LONG);
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i)))
                throw new WorkspaceValidationException(ERROR_NAME_TOO_LONG);
        }
        return value;
    }

    private static boolean isReservedWorkspaceVariable(String key) {
        return "TERMUX_UBUNTU_WORKDIR".equals(key) || "TERMUX_UBUNTU_ENV_FILE".equals(key);
    }

    private String writePrivateEnvironmentFile(Map<String, String> environment) throws IOException {
        File directory = new File(ENVIRONMENT_DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Could not create private workspace environment directory: " + directory);
        try {
            Os.chmod(directory.getAbsolutePath(), 0700);
        } catch (ErrnoException e) {
            throw new IOException("Could not protect the private workspace environment directory.", e);
        }
        String id = UUID.randomUUID().toString();
        File destination = new File(directory, id + ".env");
        File temporary = new File(directory, id + ".tmp");
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            content.append("export ").append(entry.getKey()).append('=')
                .append(shellQuote(entry.getValue())).append('\n');
        }
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Os.chmod(temporary.getAbsolutePath(), 0600);
        } catch (ErrnoException e) {
            temporary.delete();
            throw new IOException("Could not protect the private workspace environment file.", e);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not save the private workspace environment file.");
        }
        return destination.getAbsolutePath();
    }

    private void deleteEnvironmentFile(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File directory = new File(ENVIRONMENT_DIRECTORY).getCanonicalFile();
            File file = new File(path).getCanonicalFile();
            if (directory.equals(file.getParentFile()) && file.getName().endsWith(".env")) file.delete();
        } catch (IOException ignored) {
            // Stale temporary files are pruned when the service reconnects.
        }
    }

    private void ensureLoaded() {
        if (cachedWorkspaces != null && cachedBindings != null) return;
        cachedWorkspaces = loadWorkspaces();
        cachedBindings = loadBindings();
    }

    private List<Workspace> loadWorkspaces() {
        ArrayList<Workspace> workspaces = new ArrayList<>();
        String serialized = preferences.getString(KEY_WORKSPACES, "[]");
        try {
            JSONArray array = new JSONArray(serialized);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                JSONObject environmentObject = item.optJSONObject("environment");
                LinkedHashMap<String, String> environment = new LinkedHashMap<>();
                if (environmentObject != null) {
                    Iterator<String> keys = environmentObject.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String variableValue = environmentObject.optString(key, "");
                        if (ENVIRONMENT_KEY.matcher(key).matches() && !isReservedWorkspaceVariable(key)
                            && variableValue.indexOf('\n') < 0 && variableValue.indexOf('\r') < 0
                            && variableValue.indexOf('\0') < 0 && environment.size() < MAX_ENVIRONMENT_VARIABLES) {
                            environment.put(key, variableValue);
                        }
                    }
                }
                try {
                    String id = item.optString("id", "");
                    String name = validateName(item.optString("name", ""));
                    String directory = normalizeUbuntuDirectory(item.optString("workingDirectory", ""));
                    if (id.isEmpty()) continue;
                    workspaces.add(new Workspace(id, name, directory, environment));
                } catch (WorkspaceValidationException ignored) {
                    // Skip a profile whose persisted data is malformed instead of breaking the terminal UI.
                }
            }
        } catch (JSONException ignored) {
            // An invalid preferences value is treated as an empty profile list and can be recreated.
        }
        return workspaces;
    }

    private LinkedHashMap<String, SessionBinding> loadBindings() {
        LinkedHashMap<String, SessionBinding> bindings = new LinkedHashMap<>();
        String serialized = preferences.getString(KEY_SESSION_BINDINGS, "[]");
        try {
            JSONArray array = new JSONArray(serialized);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String handle = item.optString("handle", "");
                if (handle.isEmpty()) continue;
                String workspaceId = item.optString("workspaceId", "");
                if (workspaceId.isEmpty()) workspaceId = null;
                String environmentFilePath = item.optString("environmentFilePath", "");
                if (environmentFilePath.isEmpty()) environmentFilePath = null;
                bindings.put(handle, new SessionBinding(workspaceId, environmentFilePath));
            }
        } catch (JSONException ignored) {
            // Stale or malformed binding data is safely discarded.
        }
        return bindings;
    }

    private void persistWorkspaces() {
        JSONArray array = new JSONArray();
        for (Workspace workspace : cachedWorkspaces) {
            JSONObject item = new JSONObject();
            JSONObject environment = new JSONObject();
            try {
                item.put("id", workspace.id);
                item.put("name", workspace.name);
                item.put("workingDirectory", workspace.workingDirectory);
                for (Map.Entry<String, String> entry : workspace.environment.entrySet())
                    environment.put(entry.getKey(), entry.getValue());
                item.put("environment", environment);
                array.put(item);
            } catch (JSONException ignored) {
                // Validated profile values should always serialize; skip a single bad entry if platform JSON fails.
            }
        }
        preferences.edit().putString(KEY_WORKSPACES, array.toString()).apply();
    }

    private void persistBindings() {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, SessionBinding> entry : cachedBindings.entrySet()) {
            JSONObject item = new JSONObject();
            try {
                item.put("handle", entry.getKey());
                if (entry.getValue().workspaceId != null) item.put("workspaceId", entry.getValue().workspaceId);
                if (entry.getValue().environmentFilePath != null)
                    item.put("environmentFilePath", entry.getValue().environmentFilePath);
                array.put(item);
            } catch (JSONException ignored) {
                // Skip corrupt bindings; session state itself is managed independently by TermuxService.
            }
        }
        preferences.edit().putString(KEY_SESSION_BINDINGS, array.toString()).apply();
    }

    public static final class Workspace {
        public final String id;
        public final String name;
        public final String workingDirectory;
        public final Map<String, String> environment;

        private Workspace(String id, String name, String workingDirectory, Map<String, String> environment) {
            this.id = id;
            this.name = name;
            this.workingDirectory = workingDirectory;
            this.environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        }
    }

    public static final class PreparedSession {
        public final String workspaceId;
        public final String environmentFilePath;
        public final HashMap<String, String> additionalEnvironment;

        private PreparedSession(String workspaceId, String environmentFilePath,
                                HashMap<String, String> additionalEnvironment) {
            this.workspaceId = workspaceId;
            this.environmentFilePath = environmentFilePath;
            this.additionalEnvironment = additionalEnvironment;
        }
    }

    private static final class SessionBinding {
        String workspaceId;
        final String environmentFilePath;

        SessionBinding(String workspaceId, String environmentFilePath) {
            this.workspaceId = workspaceId;
            this.environmentFilePath = environmentFilePath;
        }
    }

    public static final class WorkspaceValidationException extends Exception {
        public final String code;

        WorkspaceValidationException(String code) {
            super(code);
            this.code = code;
        }
    }
}
