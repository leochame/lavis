package com.lavis.cognitive.react;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Action 类单元测试
 *
 * 测试动作类的各种功能
 */
@DisplayName("Action Tests")
class ActionTest {

    @Nested
    @DisplayName("Static factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("click() should create click action with coords")
        void clickShouldCreateClickAction() {
            Action action = Action.click(500, 300);

            assertEquals(Action.ActionType.CLICK, action.getType());
            assertArrayEquals(new int[]{500, 300}, action.getCoords());
        }

        @Test
        @DisplayName("doubleClick() should create double click action")
        void doubleClickShouldCreateDoubleClickAction() {
            Action action = Action.doubleClick(100, 200);

            assertEquals(Action.ActionType.DOUBLE_CLICK, action.getType());
            assertArrayEquals(new int[]{100, 200}, action.getCoords());
        }

        @Test
        @DisplayName("rightClick() should create right click action")
        void rightClickShouldCreateRightClickAction() {
            Action action = Action.rightClick(300, 400);

            assertEquals(Action.ActionType.RIGHT_CLICK, action.getType());
            assertArrayEquals(new int[]{300, 400}, action.getCoords());
        }

        @Test
        @DisplayName("type() should create type action with text")
        void typeShouldCreateTypeAction() {
            Action action = Action.type("hello world");

            assertEquals(Action.ActionType.TYPE, action.getType());
            assertEquals("hello world", action.getText());
        }

        @Test
        @DisplayName("key() should create key action with key name")
        void keyShouldCreateKeyAction() {
            Action action = Action.key("enter");

            assertEquals(Action.ActionType.KEY, action.getType());
            assertEquals("enter", action.getKey());
        }

        @Test
        @DisplayName("scroll() should create scroll action with amount")
        void scrollShouldCreateScrollAction() {
            Action action = Action.scroll(5);

            assertEquals(Action.ActionType.SCROLL, action.getType());
            assertEquals(5, action.getAmount());
        }

        @Test
        @DisplayName("scroll() should handle negative amount for scroll up")
        void scrollShouldHandleNegativeAmount() {
            Action action = Action.scroll(-3);

            assertEquals(Action.ActionType.SCROLL, action.getType());
            assertEquals(-3, action.getAmount());
        }

        @Test
        @DisplayName("drag() should create drag action with from and to coords")
        void dragShouldCreateDragAction() {
            Action action = Action.drag(100, 100, 500, 500);

            assertEquals(Action.ActionType.DRAG, action.getType());
            assertArrayEquals(new int[]{100, 100}, action.getCoords());
            assertArrayEquals(new int[]{500, 500}, action.getToCoords());
        }

        @Test
        @DisplayName("wait() should create wait action with duration")
        void waitShouldCreateWaitAction() {
            Action action = Action.wait(1000);

            assertEquals(Action.ActionType.WAIT, action.getType());
            assertEquals(1000, action.getDuration());
        }
    }

    @Nested
    @DisplayName("isBoundaryAction() method")
    class BoundaryActionTests {

        @Test
        @DisplayName("click should be boundary action")
        void clickShouldBeBoundaryAction() {
            assertTrue(Action.click(100, 100).isBoundaryAction());
        }

        @Test
        @DisplayName("doubleClick should be boundary action")
        void doubleClickShouldBeBoundaryAction() {
            assertTrue(Action.doubleClick(100, 100).isBoundaryAction());
        }

        @Test
        @DisplayName("rightClick should be boundary action")
        void rightClickShouldBeBoundaryAction() {
            assertTrue(Action.rightClick(100, 100).isBoundaryAction());
        }

        @Test
        @DisplayName("scroll should be boundary action")
        void scrollShouldBeBoundaryAction() {
            assertTrue(Action.scroll(3).isBoundaryAction());
        }

        @Test
        @DisplayName("enter key should be boundary action")
        void enterKeyShouldBeBoundaryAction() {
            assertTrue(Action.key("enter").isBoundaryAction());
            assertTrue(Action.key("ENTER").isBoundaryAction());
        }

        @Test
        @DisplayName("type should not be boundary action")
        void typeShouldNotBeBoundaryAction() {
            assertFalse(Action.type("hello").isBoundaryAction());
        }

        @Test
        @DisplayName("tab key should not be boundary action")
        void tabKeyShouldNotBeBoundaryAction() {
            assertFalse(Action.key("tab").isBoundaryAction());
        }

        @Test
        @DisplayName("wait should not be boundary action")
        void waitShouldNotBeBoundaryAction() {
            assertFalse(Action.wait(500).isBoundaryAction());
        }

        @Test
        @DisplayName("drag should not be boundary action")
        void dragShouldNotBeBoundaryAction() {
            assertFalse(Action.drag(100, 100, 200, 200).isBoundaryAction());
        }

        @Test
        @DisplayName("null type should not be boundary action")
        void nullTypeShouldNotBeBoundaryAction() {
            Action action = Action.builder().build();
            assertFalse(action.isBoundaryAction());
        }
    }

    @Nested
    @DisplayName("isVisualImpactAction() method")
    class VisualImpactTests {

        @Test
        @DisplayName("click should have visual impact")
        void clickShouldHaveVisualImpact() {
            assertTrue(Action.click(100, 100).isVisualImpactAction());
        }

        @Test
        @DisplayName("type should have visual impact")
        void typeShouldHaveVisualImpact() {
            assertTrue(Action.type("text").isVisualImpactAction());
        }

        @Test
        @DisplayName("key should have visual impact")
        void keyShouldHaveVisualImpact() {
            assertTrue(Action.key("enter").isVisualImpactAction());
        }

        @Test
        @DisplayName("scroll should have visual impact")
        void scrollShouldHaveVisualImpact() {
            assertTrue(Action.scroll(3).isVisualImpactAction());
        }

        @Test
        @DisplayName("drag should have visual impact")
        void dragShouldHaveVisualImpact() {
            assertTrue(Action.drag(100, 100, 200, 200).isVisualImpactAction());
        }

        @Test
        @DisplayName("wait should not have visual impact")
        void waitShouldNotHaveVisualImpact() {
            assertFalse(Action.wait(500).isVisualImpactAction());
        }

        @Test
        @DisplayName("null type should not have visual impact")
        void nullTypeShouldNotHaveVisualImpact() {
            Action action = Action.builder().build();
            assertFalse(action.isVisualImpactAction());
        }
    }

    @Nested
    @DisplayName("getDescription() method")
    class DescriptionTests {

        @Test
        @DisplayName("click description should include coords")
        void clickDescriptionShouldIncludeCoords() {
            String desc = Action.click(500, 300).getDescription();
            assertEquals("click(500,300)", desc);
        }

        @Test
        @DisplayName("doubleClick description should include coords")
        void doubleClickDescriptionShouldIncludeCoords() {
            String desc = Action.doubleClick(100, 200).getDescription();
            assertEquals("doubleClick(100,200)", desc);
        }

        @Test
        @DisplayName("rightClick description should include coords")
        void rightClickDescriptionShouldIncludeCoords() {
            String desc = Action.rightClick(300, 400).getDescription();
            assertEquals("rightClick(300,400)", desc);
        }

        @Test
        @DisplayName("type description should include text")
        void typeDescriptionShouldIncludeText() {
            String desc = Action.type("hello").getDescription();
            assertEquals("type(\"hello\")", desc);
        }

        @Test
        @DisplayName("type description should truncate long text")
        void typeDescriptionShouldTruncateLongText() {
            String longText = "This is a very long text that should be truncated";
            String desc = Action.type(longText).getDescription();
            assertTrue(desc.contains("..."));
            assertTrue(desc.length() < longText.length() + 20);
        }

        @Test
        @DisplayName("key description should include key name")
        void keyDescriptionShouldIncludeKeyName() {
            String desc = Action.key("enter").getDescription();
            assertEquals("key(enter)", desc);
        }

        @Test
        @DisplayName("scroll description should include amount")
        void scrollDescriptionShouldIncludeAmount() {
            String desc = Action.scroll(5).getDescription();
            assertEquals("scroll(5)", desc);
        }

        @Test
        @DisplayName("drag description should include from and to coords")
        void dragDescriptionShouldIncludeCoords() {
            String desc = Action.drag(100, 100, 500, 500).getDescription();
            assertEquals("drag(100,100)->(500,500)", desc);
        }

        @Test
        @DisplayName("wait description should include duration")
        void waitDescriptionShouldIncludeDuration() {
            String desc = Action.wait(1000).getDescription();
            assertEquals("wait(1000ms)", desc);
        }

        @Test
        @DisplayName("null type should return unknown")
        void nullTypeShouldReturnUnknown() {
            Action action = Action.builder().build();
            assertEquals("unknown", action.getDescription());
        }

        @Test
        @DisplayName("click with null coords should handle gracefully")
        void clickWithNullCoordsShouldHandleGracefully() {
            Action action = Action.builder().type(Action.ActionType.CLICK).build();
            String desc = action.getDescription();
            assertEquals("click(0,0)", desc);
        }
    }

    @Nested
    @DisplayName("toString() method")
    class ToStringTests {

        @Test
        @DisplayName("toString should return description")
        void toStringShouldReturnDescription() {
            Action action = Action.click(500, 300);
            assertEquals(action.getDescription(), action.toString());
        }
    }

    @Nested
    @DisplayName("Builder tests")
    class BuilderTests {

        @Test
        @DisplayName("Builder should create action with all fields")
        void builderShouldCreateActionWithAllFields() {
            Action action = Action.builder()
                    .type(Action.ActionType.DRAG)
                    .coords(new int[]{100, 100})
                    .toCoords(new int[]{500, 500})
                    .text("ignored")
                    .key("ignored")
                    .amount(10)
                    .duration(1000)
                    .build();

            assertEquals(Action.ActionType.DRAG, action.getType());
            assertArrayEquals(new int[]{100, 100}, action.getCoords());
            assertArrayEquals(new int[]{500, 500}, action.getToCoords());
        }
    }
}
