package com.satori.qq.qq;

import java.util.function.BooleanSupplier;

/** QQ's own background flag is independent of Android wakelocks and process priority. */
final class MediaForeground implements AutoCloseable {
    private final Ref ref;
    private final Object session;
    private boolean entered;
    private final BooleanSupplier keepActive;

    MediaForeground(Ref ref, Object session) {
        this(ref, session, () -> false);
    }

    MediaForeground(Ref ref, Object session, BooleanSupplier keepActive) {
        this.ref = ref;
        this.session = session;
        this.keepActive = keepActive;
    }

    void refresh() {
        if (session == null) return;
        try {
            // Same JNI entry used by KernelServiceImpl.onApplicationForeground in QQ 9.3.65.
            // Does not launch an Activity, unlock the device, or change Android app importance.
            ref.call(session, "switchToFront");
            entered = true;
            Compat.observe("media.switchToFront", "ok");
        } catch (Throwable e) {
            Compat.observe("media.switchToFront", "failed");
        }
    }

    @Override public void close() {
        if (!entered || keepActive.getAsBoolean()) return;
        try {
            // Recheck at release: the user may have opened QQ during the upload.
            if (!Ref.asBool(ref.callS("mqq.app.Foreground", "isCurrentProcessForeground"))) {
                ref.call(session, "switchToBackGround");
            }
        } catch (Throwable e) {
            Compat.observe("media.restoreBackground", "failed");
        }
    }
}
