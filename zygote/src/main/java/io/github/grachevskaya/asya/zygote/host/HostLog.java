package io.github.grachevskaya.asya.zygote.host;

import android.util.Log;

/**
 * The host's own lines in logcat, under one tag, so that everything about this -- whether the
 * wait started, whether the instrumentation was replaced, whether the manager was built -- can be
 * read in one place while it is being brought up.
 */
public final class HostLog {

    public static final String TAG = "AsyaHost";

    public static void i(String format, Object... args) {
        Log.i(TAG, args.length == 0 ? format : String.format(format, args));
    }

    public static void e(String format, Object... args) {
        Log.e(TAG, args.length == 0 ? format : String.format(format, args));
    }

    public static void e(Throwable cause, String format, Object... args) {
        Log.e(TAG, args.length == 0 ? format : String.format(format, args), cause);
    }
}
