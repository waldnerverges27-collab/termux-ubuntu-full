package com.termux.app.terminal;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A read-only native browser for the active Ubuntu rootfs or Termux home. */
public final class TermuxFileExplorerDialog {

    private TermuxFileExplorerDialog() {
    }

    public static void show(TermuxActivity activity) {
        if (activity == null || activity.isFinishing()) return;
        new Browser(activity).show();
    }

    private static final class Browser {
        private final TermuxActivity activity;
        private final Dialog dialog;
        private static final int LOCATION_CURRENT_ENVIRONMENT = 0;
        private static final int LOCATION_SHARED_STORAGE = 1;

        private final File ubuntuRootDirectory;
        private final File termuxHomeDirectory;
        private final File sharedStorageDirectory;
        private File rootDirectory;
        private final boolean ubuntuMode;
        private boolean sharedStorageMode;
        private final FileListAdapter adapter;
        private File currentDirectory;
        private TextView locationView;
        private TextView emptyView;
        private EditText searchView;
        private ListView listView;
        private final List<File> directoryEntries = new ArrayList<>();
        private String searchQuery = "";
        private boolean showHiddenFiles;
        private boolean directoryUnavailable;

        Browser(TermuxActivity activity) {
            this.activity = activity;
            ubuntuRootDirectory = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                "var/lib/proot-distro/installed-rootfs/ubuntu");
            termuxHomeDirectory = TermuxConstants.TERMUX_HOME_DIR;
            sharedStorageDirectory = new File(termuxHomeDirectory, "storage/shared");
            TerminalSession session = activity.getCurrentSession();
            String currentWorkingDirectory = session == null ? null : session.getCwd();
            String ubuntuRootPath = ubuntuRootDirectory.getAbsolutePath();
            boolean inTermuxFiles = currentWorkingDirectory != null &&
                currentWorkingDirectory.startsWith(TermuxConstants.TERMUX_FILES_DIR_PATH) &&
                !currentWorkingDirectory.startsWith(ubuntuRootPath);
            ubuntuMode = ubuntuRootDirectory.isDirectory() && !inTermuxFiles;
            sharedStorageMode = false;
            rootDirectory = ubuntuMode ? ubuntuRootDirectory : termuxHomeDirectory;
            currentDirectory = findSessionDirectory(rootDirectory, ubuntuMode);
            adapter = new FileListAdapter(activity);
            dialog = new Dialog(activity, R.style.Theme_TermuxPremiumDialog);
        }

        void show() {
            View content = LayoutInflater.from(activity).inflate(R.layout.dialog_file_explorer, null, false);
            locationView = content.findViewById(R.id.file_explorer_location);
            emptyView = content.findViewById(R.id.file_explorer_empty);
            searchView = content.findViewById(R.id.file_explorer_search);
            CheckBox showHidden = content.findViewById(R.id.file_explorer_show_hidden);
            listView = content.findViewById(R.id.file_explorer_list);
            searchView.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    searchQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                    applyFilter();
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });
            showHidden.setOnCheckedChangeListener((buttonView, isChecked) -> {
                showHiddenFiles = isChecked;
                applyFilter();
            });
            listView.setAdapter(adapter);
            listView.setEmptyView(emptyView);
            listView.setOnItemClickListener((parent, view, position, id) -> {
                File file = adapter.getItem(position);
                if (file == null) return;
                if (file.isDirectory()) navigateInto(file);
                else showFileActions(file);
            });
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                File file = adapter.getItem(position);
                if (file != null) showFileActions(file);
                return true;
            });

            content.findViewById(R.id.file_explorer_close).setOnClickListener(v -> dialog.dismiss());
            content.findViewById(R.id.file_explorer_change_location).setOnClickListener(v -> showLocationPicker());
            content.findViewById(R.id.file_explorer_home).setOnClickListener(v -> {
                currentDirectory = rootDirectory;
                clearSearch();
                refresh();
            });
            content.findViewById(R.id.file_explorer_up).setOnClickListener(v -> navigateUp());

            dialog.setContentView(content);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);
            dialog.show();

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.dimAmount = 0.58f;
                window.setAttributes(attributes);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

                int width = activity.getResources().getDisplayMetrics().widthPixels;
                int height = activity.getResources().getDisplayMetrics().heightPixels;
                int margin = dp(24);
                window.setLayout(Math.min(dp(560), width - margin * 2), Math.min(dp(620), Math.round(height * 0.82f)));
            }
            refresh();
        }

        private void showLocationPicker() {
            List<Integer> locations = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            locations.add(LOCATION_CURRENT_ENVIRONMENT);
            labels.add(getString(ubuntuMode ? R.string.premium_file_explorer_ubuntu
                : R.string.premium_file_explorer_termux));
            locations.add(LOCATION_SHARED_STORAGE);
            labels.add(getString(R.string.premium_file_explorer_shared_storage));

            new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                .setTitle(R.string.premium_file_explorer_choose_location)
                .setItems(labels.toArray(new String[0]), (picker, which) -> {
                    if (which < 0 || which >= locations.size()) return;
                    if (locations.get(which) == LOCATION_SHARED_STORAGE) {
                        if (!isSharedStorageAvailable()) {
                            showStoragePermissionPrompt();
                            return;
                        }
                        activateLocation(true);
                    } else {
                        activateLocation(false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private void activateLocation(boolean sharedStorage) {
            sharedStorageMode = sharedStorage;
            rootDirectory = sharedStorageMode ? sharedStorageDirectory
                : (ubuntuMode ? ubuntuRootDirectory : termuxHomeDirectory);
            currentDirectory = rootDirectory;
            clearSearch();
            refresh();
            if (sharedStorageMode && ubuntuMode) toast(R.string.premium_shared_storage_ubuntu_notice);
        }

        private boolean isSharedStorageAvailable() {
            try {
                return sharedStorageDirectory.isDirectory() && sharedStorageDirectory.canRead()
                    && sharedStorageDirectory.list() != null;
            } catch (SecurityException e) {
                return false;
            }
        }

        private void showStoragePermissionPrompt() {
            new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                .setTitle(R.string.premium_storage_permission_title)
                .setMessage(R.string.premium_storage_permission_message)
                .setPositiveButton(R.string.premium_storage_permission_grant, (prompt, which) -> {
                    activity.requestStoragePermission(false);
                    toast(R.string.premium_storage_permission_pending);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private File findSessionDirectory(File root, boolean ubuntuRoot) {
            TerminalSession session = activity.getCurrentSession();
            if (session == null) return root;
            String cwd = session.getCwd();
            if (TextUtils.isEmpty(cwd)) return root;

            File candidate = null;
            String rootPath = root.getAbsolutePath();
            if (cwd.equals(rootPath) || cwd.startsWith(rootPath + File.separator)) {
                candidate = new File(cwd);
            } else if (ubuntuRoot && cwd.startsWith(File.separator)) {
                candidate = new File(root, cwd.substring(1));
            } else if (!ubuntuRoot && (cwd.equals(TermuxConstants.TERMUX_HOME_DIR_PATH) ||
                cwd.startsWith(TermuxConstants.TERMUX_HOME_DIR_PATH + File.separator))) {
                candidate = new File(cwd);
            }

            if (candidate != null && candidate.isDirectory() && isInsideRoot(candidate, root)) return candidate;
            return root;
        }

        private void navigateInto(File directory) {
            if (!directory.isDirectory() || !isInsideRoot(directory, rootDirectory)) {
                toast(R.string.premium_file_explorer_unavailable);
                return;
            }
            currentDirectory = directory;
            clearSearch();
            refresh();
        }

        private void navigateUp() {
            if (currentDirectory == null || sameFile(currentDirectory, rootDirectory)) return;
            File parent = currentDirectory.getParentFile();
            if (parent != null && isInsideRoot(parent, rootDirectory)) {
                currentDirectory = parent;
            } else {
                currentDirectory = rootDirectory;
            }
            clearSearch();
            refresh();
        }

        private void refresh() {
            if (currentDirectory == null || !currentDirectory.isDirectory() || !isInsideRoot(currentDirectory, rootDirectory)) {
                currentDirectory = rootDirectory;
            }
            int locationLabel = sharedStorageMode ? R.string.premium_file_explorer_shared_storage
                : (ubuntuMode ? R.string.premium_file_explorer_ubuntu : R.string.premium_file_explorer_termux);
            locationView.setText(getString(locationLabel) + "  ·  " + displayPath(currentDirectory));

            File[] entries;
            try {
                entries = currentDirectory.listFiles(file -> file != null && file.canRead() &&
                    (file.isDirectory() || file.isFile()) && isInsideRoot(file, rootDirectory));
            } catch (SecurityException e) {
                entries = null;
            }

            directoryEntries.clear();
            directoryUnavailable = entries == null;
            if (entries != null) Collections.addAll(directoryEntries, entries);
            Collections.sort(directoryEntries, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    boolean leftDirectory = left.isDirectory();
                    boolean rightDirectory = right.isDirectory();
                    if (leftDirectory != rightDirectory) return leftDirectory ? -1 : 1;
                    return left.getName().toLowerCase(Locale.ROOT).compareTo(right.getName().toLowerCase(Locale.ROOT));
                }
            });
            applyFilter();
        }

        private void clearSearch() {
            if (searchView != null && searchView.length() > 0) {
                searchView.setText("");
            } else {
                searchQuery = "";
            }
        }

        private void applyFilter() {
            List<File> visibleFiles = new ArrayList<>();
            for (File file : directoryEntries) {
                if (!showHiddenFiles && file.getName().startsWith(".")) continue;
                if (!searchQuery.isEmpty() && !file.getName().toLowerCase(Locale.ROOT).contains(searchQuery)) continue;
                visibleFiles.add(file);
            }
            adapter.setFiles(visibleFiles);

            if (directoryUnavailable) {
                emptyView.setText(R.string.premium_file_explorer_unavailable);
            } else if (!searchQuery.isEmpty()) {
                emptyView.setText(R.string.premium_file_explorer_no_matches);
            } else if (!directoryEntries.isEmpty() && visibleFiles.isEmpty()) {
                emptyView.setText(R.string.premium_file_explorer_no_visible_files);
            } else {
                emptyView.setText(R.string.premium_file_explorer_empty);
            }
        }

        private void showFileActions(File file) {
            boolean directory = file.isDirectory();
            int[] actions = directory ? new int[] {
                R.string.premium_file_action_insert_cd,
                R.string.premium_file_action_insert_path,
                R.string.premium_file_action_copy_path
            } : new int[] {
                R.string.premium_file_action_insert_path,
                R.string.premium_file_action_copy_path
            };
            String[] labels = new String[actions.length];
            for (int i = 0; i < actions.length; i++) labels[i] = getString(actions[i]);

            new AlertDialog.Builder(activity, R.style.Theme_TermuxPremiumDialog)
                .setTitle(getString(R.string.premium_file_action_title, file.getName()))
                .setItems(labels, (actionDialog, which) -> {
                    int action = actions[which];
                    if (action == R.string.premium_file_action_copy_path) {
                        copyPath(file);
                    } else if (action == R.string.premium_file_action_insert_cd) {
                        insertIntoTerminal("cd " + quoteForShell(terminalPath(file)));
                    } else {
                        insertIntoTerminal(quoteForShell(terminalPath(file)));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        }

        private void copyPath(File file) {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("path", terminalPath(file)));
                toast(R.string.premium_file_action_copy_success);
            }
        }

        private void insertIntoTerminal(String text) {
            TerminalSession session = activity.getCurrentSession();
            if (session == null || !session.isRunning()) {
                toast(R.string.premium_file_action_no_session);
                return;
            }
            if (activity.getTerminalView() == null || activity.getTerminalView().mEmulator == null) {
                toast(R.string.premium_file_action_failed);
                return;
            }
            activity.getTerminalView().mEmulator.paste(text);
            toast(R.string.premium_file_action_insert_success);
            dialog.dismiss();
        }

        private String terminalPath(File file) {
            String rootPath = rootDirectory.getAbsolutePath();
            String filePath = file.getAbsolutePath();
            String relative = filePath.length() > rootPath.length() ? filePath.substring(rootPath.length()) : "";
            relative = relative.replace(File.separatorChar, '/');
            if (sharedStorageMode) {
                if (ubuntuMode) return relative.isEmpty() ? "/mnt/shared" : "/mnt/shared" + relative;
                return relative.isEmpty() ? sharedStorageDirectory.getAbsolutePath()
                    : sharedStorageDirectory.getAbsolutePath() + relative;
            }
            if (ubuntuMode) {
                if (relative.isEmpty()) return "/";
                return relative.startsWith("/") ? relative : "/" + relative;
            }
            return relative.isEmpty() ? TermuxConstants.TERMUX_HOME_DIR_PATH :
                TermuxConstants.TERMUX_HOME_DIR_PATH + relative;
        }

        private String displayPath(File directory) {
            String rootPath = rootDirectory.getAbsolutePath();
            String path = directory.getAbsolutePath();
            String relative = path.length() > rootPath.length() ? path.substring(rootPath.length()) : "";
            relative = relative.replace(File.separatorChar, '/');
            if (sharedStorageMode) return relative.isEmpty() ? "/" : (relative.startsWith("/") ? relative : "/" + relative);
            if (ubuntuMode) return relative.isEmpty() ? "/" : (relative.startsWith("/") ? relative : "/" + relative);
            return relative.isEmpty() ? "~" : "~/" + relative.replaceFirst("^/", "");
        }

        private boolean isInsideRoot(File candidate, File root) {
            try {
                String rootPath = root.getCanonicalPath();
                String candidatePath = candidate.getCanonicalPath();
                return candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + File.separator);
            } catch (Exception e) {
                return false;
            }
        }

        private boolean sameFile(File left, File right) {
            try {
                return left.getCanonicalPath().equals(right.getCanonicalPath());
            } catch (Exception e) {
                return left.equals(right);
            }
        }

        private String quoteForShell(String path) {
            return "'" + path.replace("'", "'\\''") + "'";
        }

        private void toast(int message) {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }

        private String getString(int resource) {
            return activity.getString(resource);
        }

        private String getString(int resource, Object... args) {
            return activity.getString(resource, args);
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }
    }

    private static final class FileListAdapter extends BaseAdapter {
        private final TermuxActivity activity;
        private final List<File> files = new ArrayList<>();

        FileListAdapter(TermuxActivity activity) {
            this.activity = activity;
        }

        void setFiles(List<File> updated) {
            files.clear();
            files.addAll(updated);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public File getItem(int position) {
            return position >= 0 && position < files.size() ? files.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            File file = getItem(position);
            return file == null ? position : file.getAbsolutePath().hashCode();
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row = convertView;
            if (row == null) row = LayoutInflater.from(activity).inflate(R.layout.item_premium_file, parent, false);
            File file = getItem(position);
            if (file == null) return row;

            boolean directory = file.isDirectory();
            TextView icon = row.findViewById(R.id.file_item_icon);
            TextView name = row.findViewById(R.id.file_item_name);
            TextView summary = row.findViewById(R.id.file_item_summary);
            icon.setText(directory ? "DIR" : "FILE");
            name.setText(file.getName());
            summary.setText(directory ? activity.getString(R.string.premium_file_explorer_folder) :
                activity.getString(R.string.premium_file_explorer_file) + "  ·  " + formatSize(file.length()));
            row.setContentDescription(file.getName() + (directory ? ", folder" : ", file"));
            return row;
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            double value = bytes / 1024.0;
            String unit = "KB";
            if (value >= 1024) {
                value /= 1024.0;
                unit = "MB";
            }
            if (value >= 1024) {
                value /= 1024.0;
                unit = "GB";
            }
            return new DecimalFormat("0.#").format(value) + " " + unit;
        }
    }
}
