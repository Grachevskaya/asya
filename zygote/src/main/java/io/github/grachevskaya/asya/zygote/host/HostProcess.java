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

/**
 * The way in, in the host process -- what Sui's SettingsProcess and its native side do together.
 *
 * Sui's Java side is called *into* at the moment the application is bound. Ours starts by itself
 * earlier than that -- this entry point runs as the process is specialized, before the framework
 * has built its ActivityThread -- so the first thing that has to happen is waiting for that
 * object to exist. Reading it too early is a null receiver on the handler field, which is exactly
 * what failed before this.
 *
 * Once it exists, a callback goes on its handler, and when the message that binds the application
 * goes by, the process's Instrumentation is replaced with one that answers the manager's launch.
 * Before that there is nothing to replace; at the launch itself is too late.
 *
 * The apk is opened at that same moment, for the same reason Sui builds its SuiApk in
 * postBindApplication: the daemon that holds it is started by the module's boot script, and a
 * process that came up before it would otherwise have nothing to load.
 */
public final class HostProcess {

    /** How long to wait for the ActivityThread, at the rate it appears. */

    /**
     * Called from the bind hook, with the ActivityThread the framework is binding. Sui's
     * postBindApplication, at the same moment.
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

            ActivityThreadBridge.setInstrumentation(thread, new ManagerInstrumentation(original, apk));
            HostLog.i("Instrumentation replaced in %s", HostConstants.HOST_PACKAGE);

            startManagerApplication(thread, apk);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not replace the instrumentation");
        }
    }

    /**
     * The manager's own application, on the host's context. The framework never starts it here --
     * there is no app of ours -- and everything the manager reaches for hangs off it: its
     * preferences, its configuration, its locale.
     */
    private static void startManagerApplication(ActivityThread thread, ManagerApk apk) {
        try {
            Application host = thread.getApplication();
            Class<?> appClass = apk.getClassLoader().loadClass("io.github.grachevskaya.asya.MyApp");
            Application application = (Application) appClass.getDeclaredConstructor().newInstance();

            attachMethod(appClass).invoke(application, host);

            appClass.getMethod("onCreate").invoke(application);

            /*
             * The directory the two halves share is made here, by this side, because this side can:
             * it is an activity of the host process, and the host's own data directory belongs to it.
             * The service cannot make it -- creating inside an application's data directory is not
             * something system_server is allowed to do, which is exactly what "could not be made"
             * said -- so the side that can, does, and the service only ever writes into it.
             */
            HostFiles.prepareStateDirectory();

            /*
             * The way in, for a host that is already running: a broadcast, answered here, by this
             * code -- which then shows the manager itself. The action script sends this first and only
             * falls back to starting the host's own activity when nothing is running to hear it, so
             * the host's screen no longer appears on the way to the manager.
             */
            registerManagerReceiver(thread);

            HostLog.i("The manager's application is up");
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not start the manager's application");
        }
    }

    /**
     * The method that takes the base context is declared by ContextWrapper, and whether Application
     * overrides it depends on the platform: on this one it does not, so it is looked for on the
     * manager's own class first and then up the chain.
     */
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

    /**
     * Listens for the action button, and answers it by showing the manager.
     *
     * Registered here, in the host process, so that none of the host's own screen takes part: the
     * broadcast is delivered to this module and this module starts the manager. Only the cold case --
     * no host running to hear it -- falls back to the shell starting the host's activity.
     */
    private static void registerManagerReceiver(ActivityThread thread) {
        try {
            Context context = systemContext(thread);

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    HostLog.i("The action button asked for the manager; showing it");
                    showManager(thread, intent);
                }
            };

            /*
             * Two ways in, and they are the same way: this module's own action, and the secret code the dialer
             * sends for this module -- *#*#2792#*#* -- which the fork this is modelled on uses as its
             * only entry. The code is broadcast with a data uri, and it gets a filter of its own,
             * because a filter's data fields apply to every action it carries: put the uri on the
             * same filter as the plain action and a broadcast that carries no data at all can never
             * match it -- which is exactly how the action button went unanswered while the receiver
             * sat there registered, listening for a name nothing was ever going to send with a uri.
             */
            register(context, receiver, new IntentFilter(HostConstants.ACTION_SHOW_MANAGER));

            IntentFilter code = new IntentFilter();
            code.addAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "android.telephony.action.SECRET_CODE"
                    : "android.provider.Telephony.SECRET_CODE");
            code.addDataScheme("android_secret_code");
            code.addDataAuthority(HostConstants.SECRET_CODE, null);
            register(context, receiver, code);

            HostLog.i("Listening for %s and the secret code %s",
                    HostConstants.ACTION_SHOW_MANAGER, HostConstants.SECRET_CODE);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not register the manager receiver");
        }
    }

    /**
     * Registers one of the manager's filters, exported so the broadcast can reach the host from
     * outside it: the shell and the dialer are both other callers, and neither is the host itself.
     */
    private static void register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    /** Starts the manager's activity in the host's own task, carrying what it checks before showing. */
    private static void showManager(ActivityThread thread, Intent from) {
        try {
            /*
             * Never while the system is still coming up: the manager activity would be created on
             * the host's main thread during boot, and refusing costs nothing -- both ways in are
             * still there once the device is up.
             */
            String bootCompleted = android.os.SystemProperties.get("sys.boot_completed");
            long uptimeMs = android.os.SystemClock.elapsedRealtime();
            if (!"1".equals(bootCompleted) || uptimeMs < 60_000L) {
                HostLog.i("Ignoring a manager request during boot (boot_completed=%s, uptime=%dms)",
                        bootCompleted, uptimeMs);
                return;
            }

            /*
             * An activity of the host, and one that is looked up rather than named: which activities a
             * system app has differs from build to build, and this only needs one of them -- the module
             * replaces it in newActivity before it can draw anything. Starting a service instead, as
             * this did for a while, brings the process up and does nothing else: no activity is ever
             * created, and the replacement never runs.
             */
            String activity = anyHostActivity(systemContext(thread));
            if (activity == null) {
                HostLog.e("The host has no activity to show the manager through");
                return;
            }

            Intent intent = new Intent();
            intent.setClassName(HostConstants.HOST_PACKAGE, activity);
            intent.putExtra(HostConstants.EXTRA, from.getIntExtra(HostConstants.EXTRA, 1));

            /*
             * The shell knows the token and now sends it; the dialer cannot, because a typed code
             * carries nothing else. Answering that code from inside the host is proof enough on its
             * own, so a token that did not travel is filled in from the one both halves share -- the
             * length the replacement reads as a refusal is what kept the code from ever showing it.
             */
            String token = from.getStringExtra(HostConstants.EXTRA_TOKEN);
            if (token == null) token = HostFiles.token();
            intent.putExtra(HostConstants.EXTRA_TOKEN, token);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);

            systemContext(thread).startActivity(intent);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not show the manager");
        }
    }

    /**
     * The host's system context.
     *
     * ActivityThread.getSystemContext is hidden -- not in the public stubs this module compiles
     * against -- but it is public on the class at runtime, so it is reached reflectively, the same way
     * the rest of what the framework does not publish is reached in here.
     */
    private static Context systemContext(ActivityThread thread) throws Exception {
        java.lang.reflect.Method method = thread.getClass().getMethod("getSystemContext");
        method.setAccessible(true);
        return (Context) method.invoke(thread);
    }

    /**
     * Puts the manager in the launcher's shortcut list, the way Sui does.
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
    private static String anyHostActivity(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(HostConstants.HOST_PACKAGE,
                            android.content.pm.PackageManager.GET_ACTIVITIES);
            if (info.activities == null) return null;

            for (android.content.pm.ActivityInfo activity : info.activities) {
                if (activity.enabled && activity.exported && activity.name != null) {
                    return activity.name;
                }
            }
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not list the host's activities");
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
