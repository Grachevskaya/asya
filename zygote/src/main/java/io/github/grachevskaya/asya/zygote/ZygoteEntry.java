package io.github.grachevskaya.asya.zygote;

import static io.github.grachevskaya.asya.zygote.util.Logcat.logELegacy;
import static io.github.grachevskaya.asya.zygote.util.Logcat.logILegacy;

import android.util.Log;

import com.v7878.r8.annotations.DoNotObfuscate;
import com.v7878.r8.annotations.DoNotObfuscateType;
import com.v7878.r8.annotations.DoNotShrink;
import com.v7878.r8.annotations.DoNotShrinkType;
import com.v7878.zygisk.ZygoteLoader;

import java.lang.reflect.Field;

import io.github.grachevskaya.asya.zygote.host.HostFiles;
import io.github.grachevskaya.asya.zygote.host.HostHook;
import io.github.grachevskaya.asya.zygote.service.SystemServerHook;

/**
 * The module's entry point, and the two moments it is called at.
 *
 * The loader calls premain once per process, before that process is specialized -- the code is
 * running in the zygote's child and the module's directory is open as a descriptor. It calls main
 * afterwards, once the process is what it is going to be.
 *
 * That split is the whole design. Anything that reaches into the framework belongs in main, and
 * only in the process it belongs to; anything in premain runs on the road every process takes out
 * of the zygote, and a mistake there takes the zygote down. What premain does here is small enough
 * to be harmless: take the manager apk and the
 * token out of the module directory while that descriptor is still open.
 *
 * The loader calls it from the process that is about to be specialized, not from the zygote itself,
 * so nothing that installs a hook belongs here. One did, once: an ART hook installed in this window
 * broke the specialization that followed it -- system_server's context transition failed, every boot
 * attempt lost its zygote, and only Zygisk Next disabling itself ended the loop. A hook that has to
 * live in the zygote can only be installed from the native side.
 */
@SuppressWarnings("all")
@DoNotObfuscateType
@DoNotShrinkType
public class ZygoteEntry {
    public static final String TAG = "ZygoteEntry";

    @DoNotObfuscate
    @DoNotShrink
    public static void premain() throws Throwable {
        try {
            String moduleDir = ZygoteLoader.getModuleDir();

            if (moduleDir != null) {
                HostFiles.takeFromModuleDirectory(moduleDir);
            }
        } catch (Throwable th) {
            // Nothing here may throw: this runs for every process the module is part of. A failure
            // is logged and not swallowed, so that one of these can never hide a boot again.
            Log.e(TAG, "premain", th);
        }
    }

    @DoNotObfuscate
    @DoNotShrink
    public static void main() throws Throwable {
        logILegacy(TAG, String.format("Injected into %s - %s", ZygoteLoader.getPackageName(),
                io.github.grachevskaya.asya.common.BuildConfig.APP_VERSION_NAME));

        try {
            /*
             * Which half belongs to which process is decided by the name the loader reports -- the
             * same thing Sui reads out of the specialize arguments. Read reflectively, because the
             * class on the compile classpath is not always the newest one; the package name is the
             * fallback, since the system process is the "android" package.
             */
            String process = ZygoteLoader.getPackageName();
            if ("android".equals(process) || "system_server".equals(process)) {
                SystemServerHook.init();
                logILegacy(TAG, "System server side installed");
            } else {
                HostHook.INSTANCE.install();
                logILegacy(TAG, "The manager's way in is in place");
            }
        } catch (Throwable th) {
            logELegacy(TAG, "An exception occurred while installing this module's half", th);
        }
    }


}
