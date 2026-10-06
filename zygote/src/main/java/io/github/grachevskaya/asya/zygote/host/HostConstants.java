package io.github.grachevskaya.asya.zygote.host;

/**
 * What the host side needs to know about this module.
 *
 * The launch carries an int under [EXTRA] and a token under [EXTRA_TOKEN]; both have to be right
 * before the manager is shown, so that an ordinary launch from anything else looks ordinary. The
 * token is written into the module's state when it is installed and read by the action script.
 */
public final class HostConstants {

    public static final String EXTRA = "asya_host";
    public static final String EXTRA_TOKEN = "asya_host_token";

    /**
     * The action the action button sends when the host is already running. Answered inside the host
     * process by the module itself, which then shows the manager -- so the host's own screen is never
     * started first, which is what made it appear for a moment on every launch.
     */
    public static final String ACTION_SHOW_MANAGER = "io.github.grachevskaya.asya.action.SHOW_MANAGER";

    /**
     * The secret code the manager answers to, the way the fork this is modelled on does it: the
     * dialer's *#*#784784#*#* reaches the host as a broadcast carrying this code, and the module's
     * receiver -- which matches exactly this authority -- shows the manager.
     */
    public static final String SECRET_CODE = "2792";

    public static final String HOST_PACKAGE = "com.android.systemui";

    /** The manager's own package: the name its resources are looked up under, not an installed app. */
    public static final String MANAGER_PACKAGE = "io.github.grachevskaya.asya";

    /**
     * The name the module's daemon registers the bridge under, and the name the host asks for.
     * Two transactions live behind it: the apk, as an open descriptor, and the token a launch has
     * to carry. It is Sui's arrangement, with its own name.
     */
    public static final String SERVICE_NAME = "asya";

    public static final String MODULE_DIR = "/data/adb/modules/asya";
    public static final String TOKEN_FILE = MODULE_DIR + "/state/host_token";

    public static final String MANAGER_ACTIVITY =
            "io.github.grachevskaya.asya.ui.activity.MainActivity";
}
