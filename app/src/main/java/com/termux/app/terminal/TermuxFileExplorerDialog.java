package com.termux.app.terminal;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
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
        private final File rootDirectory;
        private final boolean ubuntuMode;
        private final File initialDirectory;
        private final FileListAdapter adapter;
        private File currentDirectory;
        private TextView locationView;
        private TextView emptyView;
        private ListView listView;

        Browser(TermuxActivity activity) {
            this.activity = activity;
            File ubuntuRoot = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                "var/lib/proot-distro/installed-rootfs/ubuntu");
            TerminalSession session = activity.getCurrentSession();
            String currentWorkingDirectory = session == null ? null : session.getCwd();
            String ubuntuRootPath = ubuntuRoot.getAbsolutePath();
            boolean inTermuxFiles = currentWorkingDirectory != null &&
                currentWorkingDirectory.startsWith(TermuxConstants.TERMUX_FILES_DIR_PATH) &&
                !currentWorkingDirectory.startsWith(ubuntuRootPath);
            ubuntuMode = ubuntuRoot.isDirectory() && !inTermuxFiles;
            rootDirectory = ubuntuMode ? ubuntuRoot : TermuxConstants.TERMUX_HOME_DIR;
            initialDirectory = findSessionDirectory(rootDirectory, ubuntuMode);
            currentDirectory = initialDirectory;
            adapter = new FileListAdapter(activity);
            dialog = new Dialog(activity, R.style.Theme_TermuxPremiumDialog);
        }

        void show() {
            View content = LayoutInflater.from(activity).inflate(R.layout.dialog_file_explorer, null, false);
            locationView = content.findViewById(R.id.file_explorer_location);
            emptyView = content.findViewById(R.id.file_explorer_empty);
            listView = content.findViewById(R.id.file_explorer_list);
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
            content.findViewById(R.id.file_explorer_home).setOnClickListener(v -> {
                currentDirectory = rootDirectory;
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
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

                int width = activity.getResources().getDisplayMetrics().widthPixels;
                int height = activity.getResources().getDisplayMetrics().heightPixels;
                int margin = dp(24);
                window.setLayout(Math.min(dp(560), width - margin * 2), Math.min(dp(620), Math.round(height * 0.82f)));
            }
            refresh();
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
            refresh();
        }

        private void navigateUp() {
            if (currentDirectory == null || sameFile(currentDirectory, rootDirectory)) return;
            File parent = currentDirectory.getParentFile();
            if (parent != null && isInsideRoot(parent, rootDirectory)) {
                currentDirectory = parent;
                refresh();
            } else {
                currentDirectory = rootDirectory;
                refresh();
            }
        }

        private void refresh() {
            if (currentDirectory == null || !currentDirectory.isDirectory() || !isInsideRoot(currentDirectory, rootDirectory)) {
                currentDirectory = rootDirectory;
            }
            locationView.setText((ubuntuMode ? getString(R.string.premium_file_explorer_ubuntu) :
                getString(R.string.premium_file_explorer_termux)) + "  ·  " + displayPath(currentDirectory));

            File[] entries;
            try {
                entries = currentDirectory.listFiles(file -> file != null && file.canRead() &&
                    (file.isDirectory() || file.isFile()) && isInsideRoot(file, rootDirectory));
            } catch (SecurityException e) {
                entries = null;
            }
            List<File> files = new ArrayList<>();
            if (entries != null) Collections.addAll(files, entries);
            Collections.sort(files, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    boolean leftDirectory = left.isDirectory();
                    boolean rightDirectory = right.isDirectory();
                    if (leftDirectory != rightDirectory) return leftDirectory ? -1 : 1;
                    return left.getName().toLowerCase(Locale.ROOT).compareTo(right.getName().toLowerCase(Locale.ROOT));
                }
            });
            adapter.setFiles(files);
            emptyView.setText(entries == null ? R.string.premium_file_explorer_unavailable : R.string.premium_file_explorer_empty);
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
