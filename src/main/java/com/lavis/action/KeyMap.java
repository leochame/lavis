package com.lavis.action;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps String key names to AWT KeyEvent codes.
 * atomic protocol support.
 */
public class KeyMap {

    private static final Map<String, Integer> keyMap = new HashMap<>();

    static {
        // Modifiers
        keyMap.put("Ctrl", KeyEvent.VK_CONTROL);
        keyMap.put("Alt", KeyEvent.VK_ALT);
        keyMap.put("Shift", KeyEvent.VK_SHIFT);
        keyMap.put("Cmd", KeyEvent.VK_META);
        keyMap.put("Meta", KeyEvent.VK_META);
        keyMap.put("Win", KeyEvent.VK_WINDOWS);

        // Functional Keys
        keyMap.put("Enter", KeyEvent.VK_ENTER);
        keyMap.put("Return", KeyEvent.VK_ENTER); // Alias
        keyMap.put("Esc", KeyEvent.VK_ESCAPE);
        keyMap.put("Escape", KeyEvent.VK_ESCAPE);
        keyMap.put("Space", KeyEvent.VK_SPACE);
        keyMap.put("Tab", KeyEvent.VK_TAB);
        keyMap.put("Backspace", KeyEvent.VK_BACK_SPACE);
        keyMap.put("Delete", KeyEvent.VK_DELETE);

        // Arrows
        keyMap.put("Up", KeyEvent.VK_UP);
        keyMap.put("Down", KeyEvent.VK_DOWN);
        keyMap.put("Left", KeyEvent.VK_LEFT);
        keyMap.put("Right", KeyEvent.VK_RIGHT);

        // F-Keys
        keyMap.put("F1", KeyEvent.VK_F1);
        keyMap.put("F2", KeyEvent.VK_F2);
        keyMap.put("F3", KeyEvent.VK_F3);
        keyMap.put("F4", KeyEvent.VK_F4);
        keyMap.put("F5", KeyEvent.VK_F5);
        keyMap.put("F6", KeyEvent.VK_F6);
        keyMap.put("F7", KeyEvent.VK_F7);
        keyMap.put("F8", KeyEvent.VK_F8);
        keyMap.put("F9", KeyEvent.VK_F9);
        keyMap.put("F10", KeyEvent.VK_F10);
        keyMap.put("F11", KeyEvent.VK_F11);
        keyMap.put("F12", KeyEvent.VK_F12);

        // Standard Keys (A-Z, 0-9) - mapped dynamically if needed, but putting common
        // ones here
        // Note: For simple letters, we might handle them via character processing,
        // but for "PRESS:A", we need the key code.
        for (char c = 'A'; c <= 'Z'; c++) {
            keyMap.put(String.valueOf(c), KeyEvent.getExtendedKeyCodeForChar(c));
        }
        for (char c = '0'; c <= '9'; c++) {
            keyMap.put(String.valueOf(c), KeyEvent.getExtendedKeyCodeForChar(c));
        }
    }

    public static int getKeyCode(String keyName) {
        // Case insensitive lookup
        for (Map.Entry<String, Integer> entry : keyMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(keyName)) {
                return entry.getValue();
            }
        }

        // If single char, try to resolve
        if (keyName.length() == 1) {
            return KeyEvent.getExtendedKeyCodeForChar(keyName.charAt(0));
        }

        throw new IllegalArgumentException("Unknown key: " + keyName);
    }
}
