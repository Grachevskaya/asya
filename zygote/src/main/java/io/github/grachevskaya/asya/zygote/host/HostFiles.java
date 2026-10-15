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
    private static java.util.List<java.io.RandomAccessFile> heldState;

    /** The reported directory is there for the first process only; later forks inherit a cleared field. */
    public static void takeFromModuleDirectory(String moduleDir) {
        try {
            if (moduleDir == null || !new File(moduleDir, io.github.grachevskaya.asya.common.StatePaths.MANAGER_APK_NAME).isFile()) {
                moduleDir = findOpenModuleDirectory();
            }
            if (moduleDir == null) return;

            // The copy beside the module is read by the host, which is allowed there. Reading it
            // from here would leave a denial naming this module in the kernel log on every start, and
            // it is not needed: this process opens the module's own copy while the directory is still
            // open and hands the descriptor on, which is what the host reads the apk through.
            File apk = new File(moduleDir, io.github.grachevskaya.asya.common.StatePaths.MANAGER_APK_NAME);
            FileInputStream stream = new FileInputStream(apk);
            apkDescriptor = stream.getFD();
            openedApk = stream;
            apkPath = "/proc/self/fd/" + descriptorNumber(stream);
        } catch (Throwable ignored) {
            return;
        }
    }

    /*
     * The state beside the module is not opened here. Its directory is root-only and stays that way,
     * and a descriptor in this process would be inherited by every app forked from it -- a way to
     * read what the module keeps, handed to anything that can list its own descriptors. The service
     * reaches that state through the kernel instead, which is the only other side that is allowed in.
     */

    /** Held so the descriptor underneath it stays open for the life of the process. */
    private static FileInputStream openedApk;

    public static String apkPath() {
        return apkPath;
    }


    /** The directory the loader reported, kept for the reads that come later. */






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
