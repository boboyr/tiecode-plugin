package com.dsh.nomount;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * 换掉宿主的 Instrumentation：
 * <ul>
 *   <li>把发往代理 Activity 的启动改建成目标 APK 里的真实 Activity；</li>
 *   <li>Activity 创建前注入目标 Resources / Context / Application；</li>
 *   <li>目标 APK 内部的窗口跳转重新改写成代理 Activity（否则系统会报未安装）。</li>
 * </ul>
 * 只覆写需要的几个方法，其余方法直接转给原 Instrumentation，避免影响结绳自身。
 */
public class HostInstrumentation extends Instrumentation {

    private static final String TAG = "DSHNoMount";

    private final Instrumentation base;
    private final Context host;
    private final List<WeakReference<Activity>> injected = new ArrayList<>();
    private final Set<Activity> dumpedActivities =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    public HostInstrumentation(Instrumentation base, Context host) {
        this.base = base;
        this.host = host;
    }

    public Instrumentation getBase() {
        return base;
    }

    // ---------------------------------------------------------------- 委托

    @Override
    public void onCreate(Bundle arguments) {
        base.onCreate(arguments);
    }

    @Override
    public void onStart() {
        base.onStart();
    }

    @Override
    public void onDestroy() {
        base.onDestroy();
    }

    @Override
    public Context getContext() {
        return base.getContext();
    }

    @Override
    public Context getTargetContext() {
        return base.getTargetContext();
    }

    @Override
    public ComponentName getComponentName() {
        return base.getComponentName();
    }

    @Override
    public String getProcessName() {
        return base.getProcessName();
    }

    @Override
    public boolean onException(Object obj, Throwable e) {
        return base.onException(obj, e);
    }

    @Override
    public void waitForIdle(Runnable recipient) {
        base.waitForIdle(recipient);
    }

    @Override
    public void waitForIdleSync() {
        base.waitForIdleSync();
    }

    @Override
    public void runOnMainSync(Runnable runner) {
        base.runOnMainSync(runner);
    }

    @Override
    public Activity startActivitySync(Intent intent) {
        return base.startActivitySync(intent);
    }

    @Override
    public Application newApplication(ClassLoader cl, String className, Context context)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return base.newApplication(cl, className, context);
    }

    @Override
    public void callApplicationOnCreate(Application app) {
        base.callApplicationOnCreate(app);
    }

    // ------------------------------------------------------- 免安装关键路径

    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        String target = intent == null ? null : intent.getStringExtra(ApkRuntime.EXTRA_TARGET);
        if (target != null && ApkRuntime.classLoader != null) {
            Log.i(TAG, "newActivity: 代理 -> " + target);
            return base.newActivity(ApkRuntime.classLoader, target, intent);
        }
        return base.newActivity(cl, className, intent);
    }

    @Override
    public Activity newActivity(Class<?> clazz, Context context, IBinder token, Application application,
                               Intent intent, ActivityInfo info, CharSequence title, Activity parent,
                               String id, Object lastNonConfigurationInstance)
            throws IllegalAccessException, InstantiationException {
        String target = intent == null ? null : intent.getStringExtra(ApkRuntime.EXTRA_TARGET);
        if (target != null && ApkRuntime.classLoader != null) {
            try {
                return base.newActivity(ApkRuntime.classLoader, target, intent);
            } catch (ClassNotFoundException e) {
                throw new InstantiationException(e.getMessage());
            }
        }
        return base.newActivity(clazz, context, token, application, intent, info, title, parent, id,
                lastNonConfigurationInstance);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        injectIfTarget(activity);
        try {
            base.callActivityOnCreate(activity, icicle);
        } catch (RuntimeException | Error t) {
            Log.e(TAG, "目标 Activity onCreate 抛异常: " + activity.getClass().getName(), t);
            throw t;
        }
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        injectIfTarget(activity);
        base.callActivityOnCreate(activity, icicle, persistentState);
    }

    @Override
    public void callActivityOnResume(Activity activity) {
        logLifecycle("onResume", activity);
        base.callActivityOnResume(activity);
    }

    @Override
    public void callActivityOnPause(Activity activity) {
        logLifecycle("onPause", activity);
        base.callActivityOnPause(activity);
    }

    @Override
    public void callActivityOnStop(Activity activity) {
        logLifecycle("onStop", activity);
        base.callActivityOnStop(activity);
    }

    @Override
    public void callActivityOnDestroy(Activity activity) {
        logLifecycle("onDestroy", activity);
        base.callActivityOnDestroy(activity);
    }

    private void logLifecycle(String event, Activity activity) {
        ClassLoader loader = ApkRuntime.classLoader;
        if (loader != null && activity.getClass().getClassLoader() == loader) {
            Log.i(TAG, "目标窗口 " + event + ": " + activity.getClass().getName());
            if ("onResume".equals(event) && dumpedActivities.add(activity)) {
                dumpViewTreeLater(activity);
            }
        }
    }

    /** 目标窗口 resume 后把 view 树打进日志：不依赖截屏就能判断「画出来没有、多大、什么内容」。 */
    private void dumpViewTreeLater(final Activity activity) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                View decor = activity.getWindow().getDecorView();
                Log.i(TAG, "窗口: " + decor.getClass().getName() + " " + decor.getWidth() + "x"
                        + decor.getHeight() + " vis=" + decor.getVisibility());
                dumpView(decor, 0);
            } catch (Throwable t) {
                Log.w(TAG, "视图树诊断失败", t);
            }
        }, 900L);
    }

    private static void dumpView(View view, int depth) {
        if (view == null || depth > 10) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append(' ');
        }
        sb.append(view.getClass().getName()).append(' ').append(view.getWidth()).append('x')
                .append(view.getHeight()).append(" vis=").append(view.getVisibility());
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                sb.append(" text=").append(text);
            }
        }
        Log.i(TAG, "  " + sb);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpView(group.getChildAt(i), depth + 1);
            }
        }
    }

    @Override
    public void callActivityOnNewIntent(Activity activity, Intent intent) {
        injectIfTarget(activity);
        base.callActivityOnNewIntent(activity, intent);
    }

    private void injectIfTarget(Activity activity) {
        ClassLoader loader = ApkRuntime.classLoader;
        if (loader == null || activity.getClass().getClassLoader() != loader) {
            return;
        }
        for (WeakReference<Activity> ref : injected) {
            if (ref.get() == activity) {
                return;
            }
        }
        injected.add(new WeakReference<>(activity));
        ApkRuntime.injectActivity(activity, host);
    }

    // ------------------------------------------------- 目标 APK 内部跳转改写
    // 下面几个方法在公开 SDK 里是隐藏 API，不能写 @Override，
    // 但方法签名与框架一致，运行时会被正常分派到。

    public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token,
                                            Activity target, Intent intent, int requestCode) {
        return execStartActivity(who, contextThread, token, target, intent, requestCode, null);
    }

    public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token,
                                            Activity target, Intent intent, int requestCode,
                                            Bundle options) {
        rewriteIfTargetPackage(intent);
        try {
            Object result = Reflect.call(Instrumentation.class, base, "execStartActivity",
                    who, contextThread, token, target, intent, requestCode, options);
            return result instanceof ActivityResult ? (ActivityResult) result : null;
        } catch (Throwable t) {
            Log.w(TAG, "转交宿主 Instrumentation 失败，退回直接启动", t);
            try {
                if (who != null) {
                    if (options != null) {
                        who.startActivity(intent, options);
                    } else {
                        who.startActivity(intent);
                    }
                }
            } catch (Throwable inner) {
                Log.e(TAG, "退回启动也失败", inner);
            }
            return null;
        }
    }

    private void rewriteIfTargetPackage(Intent intent) {
        if (intent == null || ApkRuntime.packageName == null) {
            return;
        }
        ComponentName cn = intent.getComponent();
        if (cn == null || !ApkRuntime.packageName.equals(cn.getPackageName())) {
            return;
        }
        String name = cn.getClassName();
        if (name.startsWith(".")) {
            name = ApkRuntime.packageName + name;
        }
        intent.setClassName(host.getPackageName(), ApkRuntime.PROXY_ACTIVITY);
        intent.putExtra(ApkRuntime.EXTRA_TARGET, name);
        intent.putExtra(ApkRuntime.EXTRA_SOURCE, ApkRuntime.sourcePath);
        Log.i(TAG, "内部跳转改写为代理窗口: " + name);
    }
}
