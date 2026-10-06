package io.github.grachevskaya.asya.zygote.host;

import android.app.ActivityThread;
import android.app.Instrumentation;
import android.os.Handler;

import java.lang.reflect.Field;

/**
 * The four pieces of the framework this needs to reach, by reflection.
 *
 * It is the same set Sui's ActivityThreadUtil and HandlerUtil reach for, and for the same reason:
 * the moment to act is the message that binds the application, and what gets replaced once it
 * arrives is the instrumentation. Both live on the ActivityThread object, and the message code
 * lives in its inner handler class.
 */
public final class ActivityThreadBridge {

    private static Field handlerField;
    private static Field callbackField;
    private static Field instrumentationField;
    private static int bindApplication = -1;
    private static boolean ready;

    public static void init() {
        try {
            handlerField = ActivityThread.class.getDeclaredField("mH");
            handlerField.setAccessible(true);

            callbackField = Handler.class.getDeclaredField("mCallback");
            callbackField.setAccessible(true);

            instrumentationField = ActivityThread.class.getDeclaredField("mInstrumentation");
            instrumentationField.setAccessible(true);

            Field code = Class.forName(ActivityThread.class.getName() + "$H")
                    .getDeclaredField("BIND_APPLICATION");
            code.setAccessible(true);
            bindApplication = code.getInt(null);

            ready = true;
        } catch (Throwable cause) {
            HostLog.e(cause, "This framework is not shaped the way the host needs");
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static Handler handler(ActivityThread thread) throws Exception {
        return (Handler) handlerField.get(thread);
    }

    public static Handler.Callback callback(Handler handler) {
        try {
            return (Handler.Callback) callbackField.get(handler);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setCallback(Handler handler, Handler.Callback callback) {
        try {
            callbackField.set(handler, callback);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not put a callback on the handler");
        }
    }

    public static Instrumentation instrumentation(ActivityThread thread) {
        try {
            return (Instrumentation) instrumentationField.get(thread);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void setInstrumentation(ActivityThread thread, Instrumentation instrumentation) {
        try {
            instrumentationField.set(thread, instrumentation);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not replace the instrumentation");
        }
    }

    public static boolean isBindApplication(int what) {
        return what == bindApplication;
    }

    private ActivityThreadBridge() {
    }
}
