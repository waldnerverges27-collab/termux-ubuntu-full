package com.termux.app.terminal;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TermuxLaptopModeTest {

    @Test
    public void defaultShortcutsCoverDesktopActionsWithoutDuplicates() {
        Map<String, TermuxLaptopMode.Shortcut> shortcuts = TermuxLaptopMode.getDefaultShortcuts();
        assertEquals(7, shortcuts.size());
        for (Map.Entry<String, TermuxLaptopMode.Shortcut> entry : shortcuts.entrySet()) {
            assertNull(TermuxLaptopMode.findConflict(shortcuts, entry.getKey(), entry.getValue()));
        }
    }

    @Test
    public void defaultShortcutsMatchThePublishedBindings() {
        Map<String, TermuxLaptopMode.Shortcut> shortcuts = TermuxLaptopMode.getDefaultShortcuts();
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_P,
            TermuxLaptopMode.MOD_CTRL | TermuxLaptopMode.MOD_SHIFT),
            shortcuts.get(TermuxLaptopMode.ACTION_PALETTE));
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_F, TermuxLaptopMode.MOD_ALT),
            shortcuts.get(TermuxLaptopMode.ACTION_FILES));
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_W, TermuxLaptopMode.MOD_ALT),
            shortcuts.get(TermuxLaptopMode.ACTION_WORKSPACES));
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_SLASH,
            TermuxLaptopMode.MOD_CTRL | TermuxLaptopMode.MOD_SHIFT),
            shortcuts.get(TermuxLaptopMode.ACTION_GUIDE));
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_T,
            TermuxLaptopMode.MOD_CTRL | TermuxLaptopMode.MOD_SHIFT),
            shortcuts.get(TermuxLaptopMode.ACTION_NEW_SESSION));
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_PAGE_UP, TermuxLaptopMode.MOD_CTRL),
            shortcuts.get(TermuxLaptopMode.ACTION_PREVIOUS_SESSION));
        assertEquals(new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_PAGE_DOWN, TermuxLaptopMode.MOD_CTRL),
            shortcuts.get(TermuxLaptopMode.ACTION_NEXT_SESSION));
    }

    @Test
    public void conflictDetectionReturnsTheOtherAction() {
        Map<String, TermuxLaptopMode.Shortcut> shortcuts = new LinkedHashMap<>();
        shortcuts.put(TermuxLaptopMode.ACTION_PALETTE,
            new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_P, TermuxLaptopMode.MOD_CTRL | TermuxLaptopMode.MOD_SHIFT));
        shortcuts.put(TermuxLaptopMode.ACTION_FILES,
            new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_F, TermuxLaptopMode.MOD_ALT));

        assertEquals(TermuxLaptopMode.ACTION_FILES,
            TermuxLaptopMode.findConflict(shortcuts, TermuxLaptopMode.ACTION_WORKSPACES,
                new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_F, TermuxLaptopMode.MOD_ALT)));
        assertNull(TermuxLaptopMode.findConflict(shortcuts, TermuxLaptopMode.ACTION_FILES,
            new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_F, TermuxLaptopMode.MOD_ALT)));
    }

    @Test
    public void unassignedShortcutsDoNotConflict() {
        Map<String, TermuxLaptopMode.Shortcut> shortcuts = new LinkedHashMap<>();
        shortcuts.put(TermuxLaptopMode.ACTION_PALETTE, null);
        shortcuts.put(TermuxLaptopMode.ACTION_FILES,
            new TermuxLaptopMode.Shortcut(KeyEvent.KEYCODE_F, TermuxLaptopMode.MOD_ALT));

        assertNull(TermuxLaptopMode.findConflict(shortcuts, TermuxLaptopMode.ACTION_PALETTE, null));
        assertTrue(TermuxLaptopMode.getDefinitions().length > 0);
    }
}
