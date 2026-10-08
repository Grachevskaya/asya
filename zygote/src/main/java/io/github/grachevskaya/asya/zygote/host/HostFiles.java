package io.github.grachevskaya.asya.zygote.host;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;

/**
 * What the loader hands this process before it is specialized, kept for afterwards.
 *
 * The module's directory arrives at the entry point as an open descriptor --
 * /proc/self/fd/<n> -- and that path is taken away again right after premain. The manager apk and
 * the token live in that directory, and the one moment they can be opened is there: the process
 * is still the zygote's child, and the file it opens stays open through specialization.
 *
 * So premain takes exactly these, and nothing else. It does not touch the framework, does not
 * install anything and does not log: the loader runs premain in the zygote itself, and anything
 * heavier there takes the zygote down with it.
 */
public final class HostFiles {

    private static FileDescriptor apkDescriptor;
    private static String apkPath;
    private static String token;

    /**
     * Called from premain(): light, silent, and everything in a guard.
     *
     * The directory the loader reports is only there for the first process each zygote makes -- it
     * clears its field right after premain, and every later fork inherits the cleared value. The
     * descriptor the loader was handed stays open in the process, so when the name is gone the
     * directory is found by asking /proc/self/fd what is still open.
     */
    public static void takeFromModuleDirectory(String moduleDir) {
        try {
            if (moduleDir == null || !new File(moduleDir, "manager.apk").isFile()) {
                moduleDir = findOpenModuleDirectory();
            }
            if (moduleDir == null) return;

            File apk = new File(moduleDir, "manager.apk");

            /*
             * ART finds an oat only through a real path: <apk>/oat/<isa>/manager.odex. Through
             * /proc/self/fd/<n> there is no directory to look in, which is why the root side
             * leaves a copy in this directory. The descriptor stays as the fallback.
             */
            String real = realManagerApkPath();
            if (real != null) {
                apkPath = real;
            } else {
                FileInputStream stream = new FileInputStream(apk);
                apkDescriptor = stream.getFD();
                openedApk = stream;
                apkPath = "/proc/self/fd/" + descriptorNumber(stream);
            }
        } catch (Throwable ignored) {
            return;
        }

        try {
            File tokenFile = new File(moduleDir, "state/host_token");
            byte[] bytes = new byte[(int) Math.min(tokenFile.length(), 128)];
            try (FileInputStream in = new FileInputStream(tokenFile)) {
                int read = in.read(bytes);
                if (read > 0) {
                    token = new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Held so the descriptor underneath it stays open for the life of the process. */
    private static FileInputStream openedApk;

    public static String apkPath() {
        return apkPath;
    }

    public static String token() {
        return token;
    }

    /** The prefix the shared directory is named with. Nothing about it says what is inside. */

    /**
     * The manager apk as this host's own state directory holds it, or null when that copy is not
     * there yet. That directory is where the root side puts it together with its oat file.
     */
    private static String realManagerApkPath() {
        try {
            File root = new File("/data/data/" + HostConstants.HOST_PACKAGE);
            File[] found = root.listFiles((dir, name) -> name.startsWith(STATE_PREFIX));
            if (found == null) {
                return null;
            }
            for (File dir : found) {
                File apk = new File(dir, "manager.apk");
                if (apk.isFile()) {
                    return apk.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final String STATE_PREFIX = ".cache_";

    /**
     * The directory this side and the service share, made by this side because this side can.
     *
     * This runs inside the host process, and the host's own data directory belongs to the host, so
     * making a directory here needs no permission that is not already held. The service cannot do it:
     * creating inside an application's data directory is not something system_server is allowed to
     * do, which is why this side makes it.
     */
    public static void prepareStateDirectory() {
        try {
            File root = new File("/data/data/" + HostConstants.HOST_PACKAGE);
            File[] found = root.listFiles((dir, name) -> name.startsWith(STATE_PREFIX));

            File state = (found != null && found.length > 0)
                    ? found[0]
                    : new File(root, STATE_PREFIX + Long.toHexString(System.nanoTime()));

            if (!state.isDirectory() && !state.mkdirs()) {
                HostLog.e("The state directory could not be made: %s", state);
                return;
            }

            HostLog.i("The state directory is %s", state);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not prepare the state directory");
        }
    }

    /** The module directory, by whichever descriptor still points at it. */
    private static String findOpenModuleDirectory() {
        try {
            String[] names = new File("/proc/self/fd").list();
            if (names == null) return null;

            for (String name : names) {
                try {
                    String target = android.system.Os.readlink("/proc/self/fd/" + name);
                    if (target != null && new File(target, "module.prop").isFile()) {
                        return target;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static int descriptorNumber(FileInputStream stream) throws Exception {
        //noinspection SoonBlockedPrivateApi
        java.lang.reflect.Field fd = FileInputStream.class.getDeclaredField("fd");
        fd.setAccessible(true);
        Object descriptor = fd.get(stream);
        java.lang.reflect.Field number = FileDescriptor.class.getDeclaredField("descriptor");
        number.setAccessible(true);
        return number.getInt(descriptor);
    }

    private HostFiles() {
    }
}
