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
 * 快捷设置磁贴的共用部分：root 调用走同一条后台线程，状态以看守的真实回报为准，
 * 不在磁贴里猜测。子类只决定"点一下做什么"与"怎么显示"。
 */
abstract class GuardTile extends TileService {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;

    /** 在后台线程执行一次点按，返回要提示的结果与之后的看守状态。 */
    abstract String click(GuardCommand.Result current);

    abstract void render(Tile tile, GuardCommand.Result status);

    @Override public void onStartListening() {
        super.onStartListening();
        WORKER.execute(() -> show(GuardCommand.status()));
    }

    @Override public void onClick() {
        super.onClick();
        if (busy) return;
        busy = true;
        WORKER.execute(() -> {
            try {
                String message = click(GuardCommand.status());
                show(GuardCommand.status());
                if (message != null && !message.isEmpty()) {
                    MAIN.post(() -> {
                        try { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                    });
                }
            } finally {
                busy = false;
            }
        });
    }

    private void show(GuardCommand.Result status) {
        MAIN.post(() -> {
            Tile tile = getQsTile();
            if (tile == null) return;
            if (status == null || !status.ok) {
                tile.setState(Tile.STATE_UNAVAILABLE);
                subtitle(tile, status == null ? "需要 Root" : status.error);
            } else {
                render(tile, status);
            }
            try { tile.updateTile(); } catch (Throwable ignored) {}
        });
    }

    static void subtitle(Tile tile, String text) {
        if (Build.VERSION.SDK_INT < 29) return;
        String value = text == null ? "" : text;
        tile.setSubtitle(value.length() > 40 ? value.substring(0, 40) : value);
    }
}
