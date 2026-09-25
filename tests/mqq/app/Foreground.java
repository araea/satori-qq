package mqq.app;

/** Host lifecycle stand-in, used only by JVM tests. */
public final class Foreground {
    public static boolean active;
    public static boolean isCurrentProcessForeground() { return active; }
}
