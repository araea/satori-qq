package com.satori.qq.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.satori.qq.R;

/**
 * 后台每天检查一次更新，有新版本就发一条通知（同一版本只发一次）。
 *
 * <p>不用 root：后台里弹 root 授权提示或"已授权"的提示会很突兀，所以模块的现状取最近一次前台探测留下的缓存，
 * 对不上最多是多提醒一次，点进应用马上会按真实状态重算。系统在需要时（联网、省电）自己安排执行时机，
 * 关掉自动检查或卸载应用都会取消。
 */
public final class UpdateJob extends JobService {
    private static final int JOB_ID = 0x5A71;
    private static final int NOTICE_ID = 0x5A72;
    private static final String CHANNEL = "update";
    private static final long PERIOD_MS = 24L * 60 * 60 * 1000;

    /** 按开关安排或取消；已经安排过的不重复安排，避免每次打开应用都重置计时。 */
    public static void schedule(Context context, boolean on) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!on) {
            scheduler.cancel(JOB_ID);
            return;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException refused) {
            // 排不上班只是少了后台提醒；打开应用时照样会检查，不能因此让应用起不来。
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Updater updater = Updater.get(this);
        if (!updater.auto()) {
            schedule(this, false);
            return false;
        }
        updater.checkThen(() -> {
            notify(this, updater);
            jobFinished(params, false);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static void notify(Context context, Updater updater) {
        Updater.State state = updater.peek();
        if (state.release == null || !state.plan.any() || updater.notified(state.release.tag)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "更新提醒", NotificationManager.IMPORTANCE_LOW));
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (open == null) return;
        PendingIntent tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String what = state.plan.app && state.plan.module ? "应用与模块" : state.plan.module ? "模块" : "应用";
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle(Source.LABEL + " " + state.release.headline() + " 可以更新")
                .setContentText("打开" + Source.LABEL + "，一键更新" + what + "。")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(NOTICE_ID, notice);
        updater.markNotified(state.release.tag);
    }
}
