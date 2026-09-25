package com.satori.qq.qq;

public final class MediaForegroundTest {
    public static final class Session {
        int front, back;
        public void switchToFront() { front++; }
        public void switchToBackGround() { back++; }
    }
    public static void main(String[] args) {
        Ref ref = new Ref(MediaForegroundTest.class.getClassLoader());
        Session session = new Session();
        try (MediaForeground scope = new MediaForeground(ref, session)) {
            scope.refresh(); scope.refresh();
            check(session.front == 2 && session.back == 0, "refresh while uploading");
        }
        check(session.back == 1, "restore background after completion");
        try (MediaForeground scope = new MediaForeground(ref, session)) {
            scope.refresh();
            mqq.app.Foreground.active = true;
        }
        check(session.back == 1, "opening QQ during upload must stay foreground");
        mqq.app.Foreground.active = false;
        try (MediaForeground unused = new MediaForeground(ref, session)) { }
        check(session.back == 1, "unused lease does not change state");
        try (MediaForeground scope = new MediaForeground(ref, session, () -> true)) {
            scope.refresh();
        }
        check(session.back == 1, "connected bot retains kernel foreground after media");
        System.out.println("MediaForegroundTest OK");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
