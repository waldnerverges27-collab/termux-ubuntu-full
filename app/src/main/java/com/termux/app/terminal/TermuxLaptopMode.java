package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import com.termux.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preferences, defaults and matching for the keyboard shortcuts used by the laptop/tablet UI. */
public final class TermuxLaptopMode {

    public static final int MOD_CTRL = 1;
    public static final int MOD_ALT = 1 << 1;
    public static final int MOD_SHIFT = 1 << 2;
    public static final int MOD_META = 1 << 3;
    private static final int MOD_MASK = MOD_CTRL | MOD_ALT | MOD_SHIFT | MOD_META;

    public static final String ACTION_PALETTE = "palette";
    public static final String ACTION_FILES = "files";
    public static final String ACTION_WORKSPACES = "workspaces";
    public static final String ACTION_NEW_SESSION = "new_session";
    public static final String ACTION_PREVIOUS_SESSION = "previous_session";
    public static final String ACTION_NEXT_SESSION = "next_session";
    public static final String ACTION_GUIDE = "desktop_guide";

    private static final String PREFERENCES = "termux_premium_ui";
    private static final String KEY_SHORTCUTS = "laptop_shortcuts_v1";
    private static final Object CACHE_LOCK = new Object();
    private static String cachedSerializedShortcuts;
    private static Map<String, Shortcut> cachedShortcuts;

    private static final Definition[] DEFINITIONS = new Definition[] {
        new Definition(ACTION_PALETTE, R.string.desktop_action_palette,
            new Shortcut(KeyEvent.KEYCODE_P, MOD_CTRL | MOD_SHIFT)),
        new Definition(ACTION_FILES, R.string.desktop_action_files,
            new Shortcut(KeyEvent.KEYCODE_F, MOD_ALT)),
        new Definition(ACTION_WORKSPACES, R.string.desktop_action_workspaces,
            new Shortcut(KeyEvent.KEYCODE_W, MOD_ALT)),
        new Definition(ACTION_NEW_SESSION, R.string.desktop_action_new_session,
            new Shortcut(KeyEvent.KEYCODE_T, MOD_CTRL | MOD_SHIFT)),
        new Definition(ACTION_PREVIOUS_SESSION, R.string.desktop_action_previous_session,
            new Shortcut(KeyEvent.KEYCODE_PAGE_UP, MOD_CTRL)),
        new Definition(ACTION_NEXT_SESSION, R.string.desktop_action_next_session,
            new Shortcut(KeyEvent.KEYCODE_PAGE_DOWN, MOD_CTRL)),
        new Definition(ACTION_GUIDE, R.string.desktop_action_guide,
            new Shortcut(KeyEvent.KEYCODE_SLASH, MOD_CTRL | MOD_SHIFT))
    };

    private TermuxLaptopMode() {
    }

    public static Definition[] getDefinitions() {
        return DEFINITIONS.clone();
    }

    public static Map<String, Shortcut> getDefaultShortcuts() {
        LinkedHashMap<String, Shortcut> defaults = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) defaults.put(definition.id, definition.defaultShortcut);
        return Collections.unmodifiableMap(defaults);
    }

    public static Map<String, Shortcut> getShortcuts(Context context) {
        String raw;
        try {
            raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_SHORTCUTS, null);
        } catch (RuntimeException e) {
            raw = null;
        }
        synchronized (CACHE_LOCK) {
            if ((raw == null ? cachedSerializedShortcuts == null : raw.equals(cachedSerializedShortcuts)) && cachedShortcuts != null)
                return new LinkedHashMap<>(cachedShortcuts);

            LinkedHashMap<String, Shortcut> result = new LinkedHashMap<>();
            JSONObject saved;
            try {
                saved = raw == null ? new JSONObject() : new JSONObject(raw);
            } catch (Exception e) {
                saved = new JSONObject();
            }

            for (Definition definition : DEFINITIONS) {
                Shortcut shortcut = definition.defaultShortcut;
                if (saved.has(definition.id)) {
                    if (saved.isNull(definition.id)) {
                        shortcut = null;
                    } else {
                        JSONObject object = saved.optJSONObject(definition.id);
                        if (object != null) {
                            int keyCode = object.optInt("key", KeyEvent.KEYCODE_UNKNOWN);
                            int modifiers = object.optInt("modifiers", 0);
                            if (isValidShortcut(keyCode, modifiers)) shortcut = new Shortcut(keyCode, modifiers);
                        }
                    }
                }
                result.put(definition.id, shortcut);
            }
            cachedSerializedShortcuts = raw;
            cachedShortcuts = new LinkedHashMap<>(result);
            return result;
        }
    }

    public static Shortcut getShortcut(Context context, String actionId) {
        return getShortcuts(context).get(actionId);
    }

    /** Returns the other action's id if this binding is already in use, otherwise {@code null}. */
    public static String findConflict(Map<String, Shortcut> shortcuts, String actionId, Shortcut candidate) {
        if (shortcuts == null || candidate == null) return null;
        for (Map.Entry<String, Shortcut> entry : shortcuts.entrySet()) {
            if (entry.getKey().equals(actionId)) continue;
            if (candidate.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    /** Save a shortcut after the caller has checked conflicts. A {@code null} value unassigns it. */
    public static void saveShortcut(Context context, String actionId, Shortcut shortcut) {
        if (!isKnownAction(actionId)) throw new IllegalArgumentException("Unknown shortcut action");
        if (shortcut != null && !isValidShortcut(shortcut.keyCode, shortcut.modifiers))
            throw new IllegalArgumentException("Invalid shortcut");

        Map<String, Shortcut> shortcuts = getShortcuts(context);
        if (shortcut != null && findConflict(shortcuts, actionId, shortcut) != null)
            throw new IllegalArgumentException("Duplicate keyboard shortcut");
        shortcuts.put(actionId, shortcut);
        JSONObject object = new JSONObject();
        try {
            for (Definition definition : DEFINITIONS) {
                Shortcut value = shortcuts.get(definition.id);
                if (value == null) {
                    object.put(definition.id, JSONObject.NULL);
                } else {
                    JSONObject entry = new JSONObject();
                    entry.put("key", value.keyCode);
                    entry.put("modifiers", value.modifiers);
                    object.put(definition.id, entry);
                }
            }
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY_SHORTCUTS, object.toString()).apply();
        } catch (JSONException e) {
            throw new IllegalStateException("Could not store laptop shortcuts", e);
        }
    }

    public static void resetShortcuts(Context context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY_SHORTCUTS).apply();
    }

    public static String findMatchingAction(Context context, KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0 || isModifierKey(event.getKeyCode()))
            return null;

        Shortcut pressed = new Shortcut(event.getKeyCode(), modifiersFromEvent(event));
        for (Map.Entry<String, Shortcut> entry : getShortcuts(context).entrySet()) {
            if (pressed.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    public static int modifiersFromEvent(KeyEvent event) {
        int modifiers = 0;
        if (event.isCtrlPressed()) modifiers |= MOD_CTRL;
        if (event.isAltPressed()) modifiers |= MOD_ALT;
        if (event.isShiftPressed()) modifiers |= MOD_SHIFT;
        if (event.isMetaPressed()) modifiers |= MOD_META;
        return modifiers;
    }

    public static String formatShortcut(Context context, String actionId) {
        return formatShortcut(context, getShortcut(context, actionId));
    }

    public static String formatShortcut(Context context, Shortcut shortcut) {
        if (shortcut == null) return context.getString(R.string.desktop_unassigned);
        StringBuilder label = new StringBuilder();
        if ((shortcut.modifiers & MOD_CTRL) != 0) label.append("Ctrl+");
        if ((shortcut.modifiers & MOD_ALT) != 0) label.append("Alt+");
        if ((shortcut.modifiers & MOD_SHIFT) != 0) label.append("Shift+");
        if ((shortcut.modifiers & MOD_META) != 0) label.append("Meta+");
        label.append(keyName(shortcut.keyCode));
        return label.toString();
    }

    public static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT ||
            keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT ||
            keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT ||
            keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT;
    }

    private static boolean isKnownAction(String actionId) {
        for (Definition definition : DEFINITIONS) if (definition.id.equals(actionId)) return true;
        return false;
    }

    private static boolean isValidShortcut(int keyCode, int modifiers) {
        return keyCode != KeyEvent.KEYCODE_UNKNOWN && keyCode > 0 && modifiers != 0 && (modifiers & ~MOD_MASK) == 0
            && !isModifierKey(keyCode);
    }

    private static String keyName(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z)
            return String.valueOf((char) ('A' + keyCode - KeyEvent.KEYCODE_A));
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
            return String.valueOf((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP: return "Page Up";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "Page Down";
            case KeyEvent.KEYCODE_SLASH: return "/";
            case KeyEvent.KEYCODE_BACKSLASH: return "\\";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_ESCAPE: return "Esc";
            case KeyEvent.KEYCODE_DPAD_UP: return "↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "→";
            default:
                String name = KeyEvent.keyCodeToString(keyCode);
                if (name.startsWith("KEYCODE_")) name = name.substring("KEYCODE_".length());
                return name.replace('_', ' ');
        }
    }

    public static final class Definition {
        public final String id;
        public final int titleResource;
        public final Shortcut defaultShortcut;

        Definition(String id, int titleResource, Shortcut defaultShortcut) {
            this.id = id;
            this.titleResource = titleResource;
            this.defaultShortcut = defaultShortcut;
        }
    }

    public static final class Shortcut {
        public final int keyCode;
        public final int modifiers;

        public Shortcut(int keyCode, int modifiers) {
            this.keyCode = keyCode;
            this.modifiers = modifiers;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Shortcut)) return false;
            Shortcut shortcut = (Shortcut) other;
            return keyCode == shortcut.keyCode && modifiers == shortcut.modifiers;
        }

        @Override
        public int hashCode() {
            return 31 * keyCode + modifiers;
        }
    }
}
