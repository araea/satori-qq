package com.satori.qq.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 应用被替换后的第一声：如果是我们自己用 root 替换的，就用 root 把界面重新打开。
 *
 * <p>替换会结束旧进程，root 侧的脚本不一定活得到装完，所以"重新打开"放在新版本的进程里做。
 * 后台启动 Activity 受系统限制，root 的 {@code am start} 不受。不是我们发起的更新（用户自己在系统安装器里装的）
 * 没有标记，什么也不做。
 */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        if (!Updater.get(context).relaunchPending()) return;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Shell.run("am start --user 0 -n " + Source.LAUNCH, 15_000);
            } finally {
                pending.finish();
            }
        }, "update-relaunch").start();
    }
}
