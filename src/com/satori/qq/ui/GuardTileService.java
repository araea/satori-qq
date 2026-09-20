package com.satori.qq.ui;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import com.satori.qq.guard.GuardCommand;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 快捷设置里的 Guard 总开关：单击在 ARMED / PAUSED 之间切换。
 *
 * <p>关闭默认只停止保护、不关 QQ；「停止保活并关闭 QQ」是旁边那个独立磁贴
 * （{@link GuardStopTileService}）。状态由 root 侧 {@code qqguard status --json} 给出，
 * 所以磁贴显示的是看守真实状态，不是界面的本地猜测。
 */
public final class GuardTileService extends TileService {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    @Override public void onStartListening() { super.onStartListening(); refresh(); }
    @Override public void onTileAdded() { super.onTileAdded(); refresh(); }

    @Override public void onClick() {
        super.onClick();
        if (busy) return;
        busy = true;
        WORKER.execute(() -> {
            try {
                GuardCommand.Result current = GuardCommand.status();
                GuardCommand.Result result;
                String message;
                if (!current.ok) {
                    result = current;
                    message = current.error;
                } else if (current.armed()) {
                    result = GuardCommand.pause();
                    message = result.ok ? "知弦守护已暂停（QQ 未关闭）" : result.error;
                } else {
                    result = GuardCommand.arm();
                    message = result.ok ? "知弦守护已开启" : result.error;
                }
                render(result);
                toast(message);
            } finally {
                busy = false;
            }
        });
    }

    private void refresh() { WORKER.execute(() -> render(GuardCommand.status())); }

    private void render(final GuardCommand.Result result) {
        MAIN.post(() -> {
            Tile tile = getQsTile();
            if (tile == null) return;
            if (result == null || !result.ok) {
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setLabel("知弦守护");
                subtitle(tile, result == null ? "需要 Root" : result.error);
            } else {
                boolean armed = result.armed();
                tile.setState(armed ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setLabel("知弦守护");
                subtitle(tile, armed ? "保活中" : "已暂停");
            }
            try { tile.updateTile(); } catch (Throwable ignored) {}
        });
    }

    private static void subtitle(Tile tile, String text) {
        if (Build.VERSION.SDK_INT < 29) return;
        if (text == null) text = "";
        if (text.length() > 40) text = text.substring(0, 40);
        tile.setSubtitle(text);
    }

    private void toast(final String text) {
        if (text == null || text.isEmpty()) return;
        MAIN.post(() -> {
            try { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
        });
    }
}
