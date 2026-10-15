package io.github.grachevskaya.asya.zygote.host;

/**
 * What the host side needs to know about this module.
 *
 * before the manager is shown, so that an ordinary launch from anything else looks ordinary. The
 * token is written into the module's state when it is installed and read by the action script.
 */
public final class HostConstants {

    public static final String EXTRA = "asya_host";

    /**
     * The action the action button sends when the host is already running. Answered inside the host
     * process by the module itself, which then shows the manager -- so the host's own screen is never
     * started first, which is what made it appear for a moment on every launch.
     */

    /**
     * The secret code the manager answers to, the way the fork this is modelled on does it: the
     * dialer's *#*#784784#*#* reaches the host as a broadcast carrying this code, and the module's
     * receiver -- which matches exactly this authority -- shows the manager.
     */
    public static final String SECRET_CODE = "2792";

    /**
     * The permission a sender must hold for either receiver to answer.
     *
     * The secret code is typed into the dialer and the action button is pressed by the module's own
     * shell, so the callers are the dialer and root; a permission held only by the platform keeps
     * every other application out. Without it any application could post the broadcast and read the
     * answer off the clock, which is what tells it this module is here.
     */
    public static final String BROADCAST_PERMISSION = "android.permission.CONTROL_INCALL_EXPERIENCE";

    public static final String HOST_PACKAGE = "com.android.systemui";


    /** The manager's own package: the name its resources are looked up under, not an installed app. */
    public static final String MANAGER_PACKAGE = "io.github.grachevskaya.asya";

    /**
     * The name the module's daemon registers the bridge under, and the name the host asks for.
     * Behind it is the apk, as an open descriptor.
     */
    public static final String SERVICE_NAME = "asya";

    public static final String MODULE_DIR = "/data/adb/modules/asya";

    /**
     * The directory the module keeps to itself: root-only, owned by the state daemon, beside the
     * module rather than inside the host's data. The salt lives here.
     */
    public static final String STATE_DIR = "/data/adb/asya";

    public static final String MANAGER_ACTIVITY =
            "io.github.grachevskaya.asya.ui.activity.MainActivity";
}
