package io.github.grachevskaya.asya.zygote.host;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;

/*
 * What the loader hands this process before it is specialized, kept for afterwards.
 *
 * The module's directory arrives as an open descriptor and the name is taken away right after premain,
 * so that is the one moment its files can be opened and held. premain takes exactly these and does
 * nothing else: anything heavier there takes the zygote down with it.
 */
public final class HostFiles {

    private static FileDescriptor apkDescriptor;
    private static String apkPath;
    private static String token;

    /** The reported directory is there for the first process only; later forks inherit a cleared field. */
    public static void takeFromModuleDirectory(String moduleDir) {
        try {
            if (moduleDir == null || !new File(moduleDir, io.github.grachevskaya.asya.common.StatePaths.MANAGER_APK_NAME).isFile()) {
                moduleDir = findOpenModuleDirectory();
            }
            if (moduleDir == null) return;

            File apk = new File(moduleDir, io.github.grachevskaya.asya.common.StatePaths.MANAGER_APK_NAME);

            // ART finds an oat only through a real path: <apk>/oat/<isa>/manager.odex, and a
            // /proc/self/fd path has no directory to look in. The descriptor is the fallback.
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

    /** The manager apk as this host's own state directory holds it, or null when it is not there. */
    private static String realManagerApkPath() {
        try {
            java.io.File directory = io.github.grachevskaya.asya.common.StatePaths.INSTANCE.stateDirOrNull();
            if (directory == null) return null;

            File apk = new File(directory, "manager.apk");
            return apk.isFile() ? apk.getAbsolutePath() : null;
        } catch (Throwable ignored) {
            return null;
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
