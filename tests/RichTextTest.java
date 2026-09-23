package com.satori.qq.qq;

import java.util.Arrays;

public final class RichTextTest {
    static final class Markdown { String content = "**你好**\r\n第二行\u0000"; }
    static final class MarkdownElement { Markdown markdownElement = new Markdown(); }
    static final class Button {
        String label;
        String visitedLabel;
        String data = "private callback token";
        Button(String label, String visitedLabel) { this.label = label; this.visitedLabel = visitedLabel; }
    }
    static final class Row { java.util.List<Button> buttons = Arrays.asList(
            new Button("打开", ""), new Button("", "重试")); }
    static final class Keyboard { java.util.List<Row> rows = Arrays.asList(new Row()); }
    static final class KeyboardElement { Keyboard inlineKeyboardElement = new Keyboard(); }

    public static void main(String[] args) {
        Ref ref = new Ref(RichTextTest.class.getClassLoader());
        eq("**你好**\n第二行", RichText.markdown(ref, new MarkdownElement()));
        eq("[打开] [重试]", RichText.keyboard(ref, new KeyboardElement()));
        if (RichText.keyboard(ref, new KeyboardElement()).contains("private"))
            throw new AssertionError("callback data leaked");
        eq("", RichText.markdown(ref, new KeyboardElement()));
        System.out.println("RichTextTest OK");
    }

    private static void eq(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
