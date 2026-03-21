package com.lavis.agent.react;

import com.lavis.agent.action.KeyMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Legacy react package smoke tests.
 *
 * The old LocalExecutor implementation has been removed in the current
 * architecture, so this test keeps a minimal regression check on keyboard
 * mapping behavior used by action execution paths.
 */
@DisplayName("KeyMap Compatibility Tests")
class LocalExecutorTest {

    @Test
    @DisplayName("Should resolve common key aliases case-insensitively")
    void shouldResolveCommonKeyAliases() {
        assertEquals(KeyEvent.VK_ENTER, KeyMap.getKeyCode("enter"));
        assertEquals(KeyEvent.VK_ESCAPE, KeyMap.getKeyCode("ESC"));
        assertEquals(KeyEvent.VK_TAB, KeyMap.getKeyCode("Tab"));
        assertEquals(KeyEvent.VK_BACK_SPACE, KeyMap.getKeyCode("backspace"));
    }

    @Test
    @DisplayName("Should resolve single-character keys")
    void shouldResolveSingleCharacterKeys() {
        assertEquals(KeyEvent.VK_A, KeyMap.getKeyCode("A"));
        assertEquals(KeyEvent.VK_1, KeyMap.getKeyCode("1"));
    }

    @Test
    @DisplayName("Should throw for unsupported key names")
    void shouldThrowForUnsupportedKeys() {
        assertThrows(IllegalArgumentException.class, () -> KeyMap.getKeyCode("unknown-key"));
    }
}
