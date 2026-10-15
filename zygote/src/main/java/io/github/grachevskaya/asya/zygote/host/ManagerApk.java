package io.github.grachevskaya.asya.zygote.host;

import android.app.Application;
import android.content.ComponentName;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * The manager apk, as the host process sees it.
 *
 * No app of this module is installed, so nothing has parsed this apk: a class loader is put over
 * it, an AssetManager gets its path added, and a Resources is built on top of that with the
 * system's own measurement and configuration. From there the activity class and the constructor
 * that takes (Application, Resources, ComponentName) -- the one written to be hosted -- are what
 * the wrapper needs.
 */
public final class ManagerApk {

    private final String apkPath;
    private final PathClassLoader classLoader;
    private final Resources resources;
    private final Class<?> managerActivityClass;
    private final Constructor<?> managerActivityConstructor;

    public ManagerApk() {
        String path = managerApkPath();

        PathClassLoader loader = null;
        Resources loaded = null;
        Class<?> activityClass = null;
        Constructor<?> activityConstructor = null;

        try {
            // A plain loader, and deliberately: the apk and this module are minified separately, so a
            // method of a shared class mentions library types each build renamed on its own. What
            // crosses between the two copies is a binder, which carries no library types at all.
            loader = new PathClassLoader(path, ClassLoader.getSystemClassLoader());
            loaded = loadResources(path, loader);
            activityClass = loader.loadClass(HostConstants.MANAGER_ACTIVITY);
            activityConstructor = activityClass.getDeclaredConstructor(
                    Application.class, Resources.class, ComponentName.class);
            HostLog.i("manager.apk loaded from %s", path);
        } catch (Throwable cause) {
            HostLog.e(cause, "Could not load %s", path);
        }

        apkPath = path;
        classLoader = loader;
        resources = loaded;
        managerActivityClass = activityClass;
        managerActivityConstructor = activityConstructor;
    }

    /**
     * A descriptor the loader opened while it was still root: the host cannot read under /data/adb,
     * and nothing of this module is left anywhere else for it to read instead.
     */
    private static String managerApkPath() {
        return HostFiles.apkPath();
    }

    /**
     * Drawables that name a class are looked up through the class loader of the Resources they are
     * inflated from, and a plain Resources answers with the host's loader, where none of the apk's
     * classes exist. This one answers with the apk's loader.
     */
    private static final class ApkResources extends Resources {
        private final ClassLoader loader;

        ApkResources(AssetManager assets, android.util.DisplayMetrics metrics,
                     android.content.res.Configuration config, ClassLoader loader) {
            super(assets, metrics, config);
            this.loader = loader;
        }

        // No @Override: the base method is hidden from the public SDK, but a method with this
        // signature still overrides it at runtime.
        public ClassLoader getClassLoader() {
            return loader;
        }
    }

    private static Resources loadResources(String apkPath, ClassLoader loader) throws Exception {
        AssetManager assets = AssetManager.class.getConstructor().newInstance();

        Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        addAssetPath.setAccessible(true);
        addAssetPath.invoke(assets, apkPath);

        Resources system = Resources.getSystem();
        return new ApkResources(assets, system.getDisplayMetrics(), system.getConfiguration(), loader);
    }

    public boolean isValid() {
        return classLoader != null && resources != null && managerActivityConstructor != null;
    }

    public String getApkPath() {
        return apkPath;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public Resources getResources() {
        return resources;
    }

    public Class<?> getManagerActivityClass() {
        return managerActivityClass;
    }

    public Constructor<?> getManagerActivityConstructor() {
        return managerActivityConstructor;
    }

    /** The interface follows the device: the host's configuration is the one it is drawn in. */
    public void updateConfiguration(Configuration newConfig) {
        Resources res = resources;
        if (res == null) return;
        DisplayMetrics metrics = res.getDisplayMetrics();
        res.updateConfiguration(newConfig, metrics);
    }
}
