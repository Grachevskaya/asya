package io.github.grachevskaya.asya.zygote.host;

import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/*
 * The way in, in the host process. It runs as the process is specialized, before the framework has
 * built its ActivityThread, so the first thing it does is wait for that object. When the message that
 * binds the application goes by, the process's Instrumentation is replaced -- before the launch, since
 * at the launch it is too late -- and the manager's apk is opened at that same moment.
 */
public final class HostProcess {

    /** The apk the module carries, kept from the moment it was opened. */
    private static ManagerApk managerApk;

    /** The manager's application is made once. */
    private static volatile boolean managerApplicationStarted;
    /**
     * Called from the bind hook, with the ActivityThread the framework is binding.
     */
    public static void onApplicationBound(ActivityThread thread) {
        ActivityThreadBridge.init();
        if (!ActivityThreadBridge.isReady()) return;

        postBindApplication(thread);
    }

    private static void postBindApplication(ActivityThread thread) {
        try {
            Instrumentation original = ActivityThreadBridge.instrumentation(thread);
            if (original == null) {
                HostLog.e("There is no instrumentation to replace");
                return;
            }
            if (original instanceof ManagerInstrumentation) return;

            ManagerApk apk = new ManagerApk();
            if (!apk.isValid()) {
                HostLog.i("The manager apk is not available; nothing will be shown from here");
                return;
            }

            managerApk = apk;

            ActivityThreadBridge.setInstrumentation(thread, new ManagerInstrumentation(original, apk));
            HostLog.i("Instrumentation replaced in %s", HostConstants.HOST_PACKAGE);

            // The manager's own object answers on the bridge, and this is the moment its application
            // is about to exist.
            HostBridge.INSTANCE.install(apk.getClassLoader());

            // The manager's application reads the configuration out of the host's own files, and those
            // are not there until the user's storage is. Started before that, it reads an empty one and
            // keeps it -- at boot, the first and only time it was started was too early. The start waits
            // for the unlock, so the read happens once, where there is something to read.
            io.github.grachevskaya.asya.common.UserUnlockKt.whenUserUnlocked(
                    thread.getApplication(),
                    () -> {
                        startManagerApplication(thread, apk);

                        return kotlin.Unit.INSTANCE;
                    });
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not replace the instrumentation");
        }
    }

    /** The manager's own application, on the host's context: the framework never starts it here. */
    private static void startManagerApplication(ActivityThread thread, ManagerApk apk) {
        if (managerApplicationStarted) return;

        try {
            Application host = thread.getApplication();
            Class<?> appClass = apk.getClassLoader().loadClass("io.github.grachevskaya.asya.MyApp");
            Application application = (Application) appClass.getDeclaredConstructor().newInstance();

            attachMethod(appClass).invoke(application, hostedContext(apk, host));

            appClass.getMethod("onCreate").invoke(application);

            // The way in for a host that is already running: the action script sends this, and only
            // falls back to starting the host's own activity when nothing is running to hear it.
            registerManagerReceiver(thread);

            managerApplicationStarted = true;

            HostLog.i("The manager's application is up");

            wakeManagerClientOnUnlock(thread, apk);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not start the manager's application");
        }
    }

    /** Touches the manager's client once the user's storage is readable, and not before. */
    private static void wakeManagerClientOnUnlock(ActivityThread thread, ManagerApk apk) {
        Application application = thread.getApplication();

        if (io.github.grachevskaya.asya.common.UserUnlockKt.storageIsUnlocked(application)) {
            HostLog.i("The user's storage is readable; waking the manager's client");
            touchManagerClient(apk);

            return;
        }

        HostLog.i("The manager's client waits for the unlock");

        io.github.grachevskaya.asya.common.UserUnlockKt.whenUserUnlocked(application, () -> {
            HostLog.i("The user's storage is readable; waking the manager's client");
            touchManagerClient(apk);

            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Reads the manager's own client object, which is what runs its initialiser: the sink the log
     * lines are written to, the sink the totals are added into, and the callback the handshake hands
     * the configuration over on. Nothing else has to be called for any of that to be in place.
     */
    private static void touchManagerClient(ManagerApk apk) {
        try {
            Class<?> type = apk.getClassLoader()
                    .loadClass("io.github.grachevskaya.asya.service.ServiceClient");

            Object client = type.getField("INSTANCE").get(null);

            HostLog.i("The manager's client is awake: %s", client != null);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not touch the manager's client");
        }
    }

    /**
     * The host's context with the manager's own resources on it: on the host's resources a string this
     * apk carries is one the table has never heard of, and the lookup throws.
     */
    private static Context hostedContext(ManagerApk apk, Context host) {
        try {
            Class<?> type = apk.getClassLoader()
                    .loadClass("io.github.grachevskaya.asya.host.HostContext");

            return (Context) type
                    .getConstructor(Context.class, android.content.res.Resources.class)
                    .newInstance(host, apk.getResources());
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not build the manager's context; the host's is used instead");
            return host;
        }
    }

    /** The application the manager runs on, made once, at the first moment it can be. */
    private static void ensureManagerApplication(ActivityThread thread) {
        ManagerApk apk = managerApk;
        if (apk == null) return;

        startManagerApplication(thread, apk);
    }

    /** attachBaseContext is declared by ContextWrapper on this platform, not by Application. */
    private static java.lang.reflect.Method attachMethod(Class<?> type) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod("attachBaseContext", Context.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep walking
            }
        }

        throw new NoSuchMethodException("attachBaseContext");
    }

    /** Answers the action button by showing the manager, so no screen of the host takes part. */
    private static void registerManagerReceiver(ActivityThread thread) {
        try {
            Context context = systemContext(thread);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    HostLog.i("The action button asked for the manager; showing it");
                    // Posted, not run here: a sender that ordered the broadcast is called back when
                    // onReceive returns, so work done synchronously is a signal an outsider can time.
                    new Handler(android.os.Looper.getMainLooper()).post(() -> showManager(thread, intent));
                }
            };

            // The secret code gets a filter of its own: a filter's data fields apply to every action
            // it carries, so a broadcast with no data can never match a filter that names a uri.
            // There is one entry point and it is the secret code: the module sends no action, so there
            // is no name for anyone to ask after. The filter names no uri, so a broadcast carrying one
            // cannot match it, and the permission keeps every application that is not the dialer out.

            IntentFilter code = new IntentFilter();
            code.addAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "android.telephony.action.SECRET_CODE"
                    : "android.provider.Telephony.SECRET_CODE");
            code.addDataScheme("android_secret_code");
            code.addDataAuthority(HostConstants.SECRET_CODE, null);
            register(context, receiver, code, HostConstants.BROADCAST_PERMISSION);

            HostLog.i("Listening for the secret code %s", HostConstants.SECRET_CODE);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not register the manager receiver");
        }
    }

    /**
     * Registered with a permission and no receiver flags. The permission keeps every caller but the
     * dialer and root out. The flags are not optional: with RECEIVER_EXPORTED the receiver stops
     * hearing a broadcast that names its own package.
     */
    private static void register(Context context, BroadcastReceiver receiver, IntentFilter filter,
                                 String permission) {
        context.registerReceiver(receiver, filter, permission, null);
    }

    /** Starts the manager's activity in the host's own task, carrying what it checks before showing. */
    private static void showManager(ActivityThread thread, Intent from) {
        try {
            // A request that arrives while the device is still locked has nothing to show yet.

            if (!io.github.grachevskaya.asya.common.UserUnlockKt.storageIsUnlocked(thread.getApplication())) {
                HostLog.i("Ignoring a manager request: the user is still locked");
                return;
            }

            ensureManagerApplication(thread);

            // An activity of the host, looked up rather than named: which ones a system app declares
            // differs between builds, and the module replaces whichever it is before it draws.
            String activity = anyHostActivity(systemContext(thread));
            if (activity == null) {
                HostLog.e("The host has no activity to show the manager through");
                return;
            }

            Intent intent = new Intent();
            // The host's own package, resolved at install rather than written here: a build that
            // renamed SystemUI would not answer to the constant.
            intent.setClassName(HostConstants.HOST_PACKAGE, activity);
            intent.putExtra(HostConstants.EXTRA, from.getIntExtra(HostConstants.EXTRA, 1));

            // A typed code carries nothing else, so a token that did not travel is filled in from the
            // one both halves share.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);

            systemContext(thread).startActivity(intent);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not show the manager");
        }
    }

    /** Hidden from the stubs this module compiles against, so it is reached reflectively. */
    private static Context systemContext(ActivityThread thread) throws Exception {
        java.lang.reflect.Method method = thread.getClass().getMethod("getSystemContext");
        method.setAccessible(true);
        return (Context) method.invoke(thread);
    }

    /**
     * Puts the manager in the launcher's shortcut list.
     *
     * A shortcut is the one entry point the system can offer for something that is not an installed
     * activity of its own: it carries an intent, it carries the logo, and it can be tapped from the
     * home screen without any of the host's screen appearing first. The intent it carries is the one
     * the receiver above answers -- so tapping it shows the manager and nothing else.
     */

    /**
     * The manager's own icon, as a bitmap.
     *
     * Looked up by name in the apk's resources rather than through a generated R class: the module is
     * not the apk, so it has no ids to point at, and getIdentifier is the way across.
     */

    /**
     * Any activity of the host, by name.
     *
     * Which activities a system app declares is not something to hard-code: it differs between builds,
     * and this only needs one of them -- the module replaces it in newActivity before it can draw. The
     * first enabled one the package manager lists is as good as any other.
     */

    /**
     * What the manager says it is, instead of the borrowed activity own answer.
     *
     * The activity the manager is shown through is a host activity, and its ActivityInfo is the host
     * one: its configuration changes, its label, its soft input mode. Everything the client decides
     * from that info was therefore decided by which host activity the package manager happened to
     * list first. This replaces those fields with the manager own, before the activity is created,
     * and nothing is added anywhere: the info lives in this process only, and the package manager
     * is never asked about it.
     */
    static void adoptManagerActivityInfo(android.app.Activity activity) {
        try {
            android.content.pm.ActivityInfo info = activityInfoOf(activity);
            if (info == null) return;

            info.configChanges = MANAGER_CONFIG_CHANGES;
            info.nonLocalizedLabel = MANAGER_LABEL;
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not adopt the manager activity info");
        }
    }

    /**
     * The changes the manager handles itself, the same set its own manifest declares.
     *
     * Written as the values themselves because the stubs this module compiles against do not carry
     * these constants: mcc, mnc, locale, touchscreen, keyboard, keyboardHidden, navigation,
     * orientation, screenLayout, uiMode, screenSize, smallestScreenSize, density, layoutDirection,
     * colorMode, fontScale and assetsPaths, in that order of the numbers below.
     */
    private static final int MANAGER_CONFIG_CHANGES =
            0x0001 | 0x0002 | 0x0004 | 0x0008 | 0x0010 | 0x0020 | 0x0040 | 0x0080
                    | 0x0100 | 0x0200 | 0x0400 | 0x0800 | 0x1000 | 0x2000 | 0x4000
                    | 0x40000000 | 0x80000000;

    /** The hidden field the activity carries its own info in, read the way the module reads hidden things. */
    private static android.content.pm.ActivityInfo activityInfoOf(android.app.Activity activity) {
        try {
            java.lang.reflect.Field field =
                    com.v7878.unsafe.Reflection.getDeclaredField(
                            android.app.Activity.class, "mActivityInfo");
            field.setAccessible(true);
            return (android.content.pm.ActivityInfo) field.get(activity);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not read the activity info");
            return null;
        }
    }

    private static final CharSequence MANAGER_LABEL = "Asya";

    /**
     * Whether the manager can be built on this activity.
     *
     * The task the manager is shown in is shaped by the activity it borrows: one that is not kept in
     * the history, or that is launched into a task of its own, is one the manager is shown in and
     * then taken out of. These are the flags to stay away from; the plain standard activity the host
     * keeps in its own task is the one wanted, and anything else is only a fallback.
     */
    private static boolean isSuitableHostActivity(android.content.pm.ActivityInfo activity) {
        // ActivityInfo.FLAG_NO_HISTORY (1 << 30) and the excludeFromRecents flag the manifest
        // parser sets; the launch modes 3 and 2 are singleInstance and singleTask.
        boolean noHistory = (activity.flags & 0x40000000) != 0;
        boolean notInRecents = (activity.flags & 0x00800000) != 0;
        boolean ownTask = activity.launchMode == 3 || activity.launchMode == 2;

        // Sui's two conditions, and the reason they are needed: the activity has to run in the host's
        // own process, and it has to be a plain standard one. A singleTop, a singleInstance or a
        // trampoline is not the window the manager can be built on -- picking one of those is how a
        // request ends up opening an activity that shows nothing.
        boolean ownProcess = activity.processName == null
                || activity.applicationInfo == null
                || HostConstants.HOST_PACKAGE.equals(activity.processName)
                || HostConstants.HOST_PACKAGE.equals(activity.applicationInfo.processName);
        boolean standard = activity.launchMode == 0;   // ActivityInfo.LAUNCH_MULTIPLE

        return !noHistory && !notInRecents && !ownTask && ownProcess && standard;
    }

    private static String anyHostActivity(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(HostConstants.HOST_PACKAGE,
                            android.content.pm.PackageManager.GET_ACTIVITIES);
            if (info.activities == null) return null;

            String fallback = null;
            for (android.content.pm.ActivityInfo activity : info.activities) {
                if (!activity.enabled || !activity.exported || activity.name == null) continue;

                if (isSuitableHostActivity(activity)) return activity.name;
                if (fallback == null) fallback = activity.name;
            }

            return fallback;
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not list the host activities");
        }

        return null;
    }

    private static String thisProcess() {
        try {
            return com.v7878.zygisk.ZygoteLoader.getPackageName();
        } catch (Throwable cause) {
            return null;
        }
    }

    private HostProcess() {
    }
}
