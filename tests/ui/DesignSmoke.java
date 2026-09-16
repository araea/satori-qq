package com.satori.qq.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Native UI, lifecycle and app-private configuration checks; never sends messages or restarts QQ. */
public final class DesignSmoke extends Instrumentation {
    private boolean dark;
    private float fontScale = 1;
    private int density;
    private Activity activity;
    private File settingsFile;
    private byte[] original;
    private boolean hadOriginal;
    private final StringBuilder report = new StringBuilder();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public Activity newActivity(ClassLoader loader, String name, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity result = super.newActivity(loader, name, intent);
        Configuration config = new Configuration(); config.uiMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO; config.fontScale = fontScale;
        if (density > 0) config.densityDpi = density;
        result.applyOverrideConfiguration(config); return result;
    }
    @Override public void onStart() {
        Bundle out = new Bundle();
        try {
            settingsFile = new File(getTargetContext().getNoBackupFilesDir(), "zhixian-control.json");
            hadOriginal = settingsFile.exists(); original = hadOriginal ? java.nio.file.Files.readAllBytes(settingsFile.toPath()) : new byte[0];
            try (FileOutputStream backup = getTargetContext().openFileOutput("design-settings-backup.json", 0)) { backup.write(original); }
            JSONObject config = new JSONObject().put("port", 3001).put("token", "").put("status_notification", true).put("foreground_keepalive", true).put("wake_lock_auto", true).put("wifi_sustain", true);
            java.nio.file.Files.write(settingsFile.toPath(), new JSONObject().put("overrides", config).put("revision", 0).toString().getBytes("UTF-8"));
            launch();
            ui(() -> {
                check(!view("saveButton").isEnabled(), "clean form");
                edit("portInput").setText("999"); call("save", new Class[]{boolean.class}, false);
                check(edit("portInput").getError() != null, "invalid port rejected inline");
                edit("portInput").setText("3101"); edit("tokenInput").setText("test-secret"); call("save", new Class[]{boolean.class}, false);
            });
            await(() -> !((Boolean) field(activity, "saving")));
            JSONObject saved = new JSONObject(new String(java.nio.file.Files.readAllBytes(settingsFile.toPath()), "UTF-8")).getJSONObject("overrides");
            check(saved.getInt("port") == 3101 && saved.getString("token").equals("test-secret"), "settings committed");
            Bundle bridge = getTargetContext().getContentResolver().call(Uri.parse("content://com.satori.qq.control"), "bootstrap", null, null);
            check(bridge != null && bridge.getLong("revision") == 1, "owner can read bootstrap revision");
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> cfgClass = loader.loadClass("com.satori.qq.Cfg"); Object cfg = cfgClass.newInstance();
            Class<?> bridgeClass = loader.loadClass("com.satori.qq.control.ControlBridge");
            long applied = (Long) bridgeClass.getMethod("bootstrap", android.content.Context.class, cfgClass, String.class).invoke(null, getTargetContext(), cfg, "0.11.0");
            check(applied == 1 && cfgClass.getField("port").getInt(cfg) == 3101, "bootstrap applies committed port and revision");
            check(cfgClass.getField("token").get(cfg).equals("test-secret"), "bootstrap applies committed token");

            ui(() -> {
                edit("portInput").setText("3201"); edit("tokenInput").setText("unsaved-secret");
                call("reveal", new Class[]{boolean.class}, true);
                check((activity.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0, "revealed token blocks screenshots");
                call("switchTab", new Class[]{int.class}, 2);
                check(edit("tokenInput").getTransformationMethod() != null, "token hides when leaving settings");
            });
            ActivityMonitor monitor = addMonitor("com.satori.qq.ui.MainActivity", null, false);
            ui(() -> activity.recreate());
            Activity replacement = monitor.waitForActivityWithTimeout(10000); check(replacement != null, "activity recreated"); activity = replacement; removeMonitor(monitor); waitForIdleSync();
            ui(() -> {
                check(edit("portInput").getText().toString().equals("3201"), "draft port survives recreation");
                check(edit("tokenInput").getText().toString().equals("unsaved-secret"), "draft token survives recreation");
                check(((Integer) field(activity, "selected")) == 2, "selected destination survives recreation");
                set(activity, "health", null); set(activity, "checkedAt", System.currentTimeMillis()); call("updateState", new Class[0]);
                check(((TextView) view("stateTitle")).getText().toString().equals("尚未连接"), "failed probe clears online state");
            });
            close();
            java.nio.file.Files.write(settingsFile.toPath(), new JSONObject().put("overrides", config).put("revision", 0).toString().getBytes("UTF-8"));
            launch();
            for (int tab = 0; tab < 3; tab++) capture("light-" + tab, tab);
            close(); dark = true; launch(); for (int tab = 0; tab < 3; tab++) capture("dark-" + tab, tab);
            close(); dark = false; fontScale = 2;
            density = Math.round(getTargetContext().getResources().getDisplayMetrics().widthPixels / 320f * 160);
            launch();
            for (int tab = 0; tab < 3; tab++) {
                final int selected = tab; ui(() -> call("switchTab", new Class[]{int.class}, selected)); waitForIdleSync();
                ui(() -> bounds(activity.getWindow().getDecorView())); capture("large-" + tab, tab);
            }
            close(); restore(); out.putString("stream", "\n" + report + "PASS: 知弦 UI\n"); finish(Activity.RESULT_OK, out);
        } catch (Throwable error) {
            try { if (activity != null) close(); restore(); } catch (Throwable ignored) {}
            out.putString("stream", "\n" + report + "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, out);
        }
    }
    private void launch() { activity = startActivitySync(new Intent().setClassName(getTargetContext(), "com.satori.qq.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); waitForIdleSync(); }
    private void close() { ui(() -> activity.finish()); waitForIdleSync(); activity = null; }
    private void restore() {
        if (settingsFile == null) return;
        try {
            if (hadOriginal) java.nio.file.Files.write(settingsFile.toPath(), original);
            else java.nio.file.Files.deleteIfExists(settingsFile.toPath());
            check(true, "original settings restored"); getTargetContext().deleteFile("design-settings-backup.json");
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void capture(String name, int tab) {
        ui(() -> call("switchTab", new Class[]{int.class}, tab)); waitForIdleSync();
        ui(() -> {
            try {
                JSONObject fixture = new JSONObject().put("name", "satori-qq").put("version", "0.11.0").put("online", true).put("listening", true).put("connections", 1).put("qq_version", "9.3.60").put("config_revision", 0).put("online_since_epoch_ms", System.currentTimeMillis() - 7200000).put("notice", "enabled/posted=yes").put("keepalive", "fgs=on").put("compat", new JSONObject().put("passed",204).put("total",204));
                set(activity, "health", fixture); set(activity, "checkedAt", System.currentTimeMillis()); call("updateState", new Class[0]); view("progress").setVisibility(View.INVISIBLE);
                ScrollView scroll = ((ScrollView[]) field(activity, "pages"))[tab]; View content = scroll.getChildAt(0);
                ViewGroup root = (ViewGroup) scroll.getParent().getParent(); View nav = root.getChildAt(1);
                check(content.getWidth() > 0 && content.getHeight() > 0, "measured " + name);
                float scale = 600f / content.getWidth(); Bitmap bitmap = Bitmap.createBitmap(600, Math.round((content.getHeight() + nav.getHeight()) * scale), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap); canvas.scale(scale, scale); Object style = field(activity, "ui"); canvas.drawColor((Integer) field(style, "surface")); content.draw(canvas); canvas.translate(0, content.getHeight()); nav.draw(canvas);
                File dir = new File(getTargetContext().getFilesDir(), "design-review"); dir.mkdirs(); try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); } bitmap.recycle();
                contrast(style, "ink", "surface"); contrast(style, "muted", "container"); contrast(style, "onPrimary", "primary"); contrast(style, "onPrimaryContainer", "primaryContainer");
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }
    private void bounds(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) check(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom(), "unclipped: " + text.getText());
        }
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i=0;i<group.getChildCount();i++) bounds(group.getChildAt(i)); }
    }
    private void contrast(Object style, String foreground, String background) { double a=luminance((Integer)field(style,foreground)), b=luminance((Integer)field(style,background)); check((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5, "contrast " + foreground); }
    private double luminance(int c) { double v=0; double[] weights={.2126,.7152,.0722}; for(int i=0;i<3;i++){double n=((c>>(16-i*8))&255)/255.; v+=weights[i]*(n<=.04045?n/12.92:Math.pow((n+.055)/1.055,2.4));}return v; }
    private interface Condition { boolean done(); }
    private void await(Condition condition) throws Exception { for(int i=0;i<100;i++){AtomicReference<Boolean> result=new AtomicReference<>(false);ui(()->result.set(condition.done()));if(result.get())return;Thread.sleep(50);}throw new AssertionError("operation timed out"); }
    private void ui(Runnable task) { AtomicReference<Throwable> error=new AtomicReference<>();runOnMainSync(()->{try{task.run();}catch(Throwable t){error.set(t);}});if(error.get()!=null)throw new AssertionError(error.get()); }
    private View view(String name) { return (View)field(activity,name); }
    private EditText edit(String name) { return (EditText)view(name); }
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);report.append("ok ").append(label).append('\n');}
    private static Object field(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new RuntimeException(e);}}
    private static void set(Object target,String name,Object value){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception e){throw new RuntimeException(e);}}
    private void call(String name,Class<?>[] types,Object...args){try{Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
}
