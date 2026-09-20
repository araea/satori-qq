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
 * 「停止保活并关闭 QQ」磁贴：先暂停 watchdog，再强停 QQ。
 *
 * <p>这是总开关之外的显式动作，单击即执行。正常关闭 QQ 后 Guard 处于 PAUSED，不会再被拉起；
 * 想恢复就点旁边的「知弦守护」磁贴，或在 KernelSU 里跑 {@code qqguard start}。
 */
public final class GuardStopTileService extends TileService {
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
                GuardCommand.Result result = GuardCommand.stopAndKill();
                render(result);
                toast(result.ok ? "已停止保活并关闭 QQ" : result.error);
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
                tile.setLabel("停止保活并关闭 QQ");
                subtitle(tile, result == null ? "需要 Root" : result.error);
            } else {
                // 这是一个动作型磁贴：Guard 在保活时可用，暂停时灰掉但仍可点。
                tile.setState(result.armed() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setLabel("停止保活并关闭 QQ");
                subtitle(tile, result.armed() ? "点一下 = 暂停并关闭 QQ" : "守护已暂停");
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
