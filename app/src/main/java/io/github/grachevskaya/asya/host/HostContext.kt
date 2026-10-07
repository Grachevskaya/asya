package io.github.grachevskaya.asya.host

import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.ContextThemeWrapper

/**
 * A context whose resources are not the host's.
 *
 * The manager is shown from inside a host process, and the layouts, strings and drawables it
 * inflates are its own -- they come out of the apk the module carries, loaded into that process.
 * Everything that asks this context for resources gets those, and everything else is the host's.
 *
 * The shape is the one Sui uses for the same problem: a wrapper on the base context that answers
 * getResources, getAssets and getTheme from the loaded apk.
 *
 * The theme is optional, and the host side constructs this by reflection: it asks for exactly
 * (Context, Resources) -- java.lang.Class.getConstructor finds declared constructors, not Kotlin's
 * default arguments -- and without the overload generated for it that lookup throws, the host
 * falls back to its own context, and the manager ends up with the host's resources: its own
 * strings, drawables and theme gone.
 */
class HostContext
    @JvmOverloads
    constructor(
        base: android.content.Context,
        private val hostedResources: Resources,
        private val hostedTheme: Resources.Theme? = null,
    ) : ContextWrapper(base) {

    override fun getResources(): Resources = hostedResources

    /**
     * The apk's own loader, not the host's.
     *
     * Layout inflation resolves classes through the context it is created from, and the host's
     * loader brings its own copy of androidx along -- so a NavHostFragment named in the manager's
     * layout came back as a class that could not be cast to the Fragment the layout asked for. This
     * loader holds the apk and nothing else, which is what makes the whole tree one set of classes.
     */
    override fun getClassLoader(): ClassLoader = javaClass.classLoader

    override fun getAssets(): AssetManager = hostedResources.assets

    override fun getTheme(): Resources.Theme =
        hostedTheme ?: hostedResources.newTheme()

    /**
     * The inflater has to belong to this context.
     *
     * LayoutInflater.from(hosted) asks here for the service, and ContextWrapper passes that on to
     * the base -- which is the host -- so the inflater that came back resolved its ids in the host's
     * table, so a lookup of an id the manager owns lands in the host's table and throws. Cloning one
     * into this context keeps every lookup in the apk, whichever way the inflater is asked for.
     */
    override fun getSystemService(name: String): Any? =
        if (name == android.content.Context.LAYOUT_INFLATER_SERVICE) {
            (super.getSystemService(name) as android.view.LayoutInflater).cloneInContext(this)
        } else {
            super.getSystemService(name)
        }

    /**
     * A configuration context has to stay on these resources.
     *
     * The locale is applied by asking the base context for a configuration context, and the base is
     * the host -- so without this the activity came back holding the host's resources, and every
     * lookup the manager made landed in the host's table. That is how a theme attribute belonging to
     * the apk ended up being resolved against MIUI's Settings drawables and killed the activity.
     */
    override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): android.content.Context {
        hostedResources.updateConfiguration(overrideConfiguration, hostedResources.displayMetrics)
        return this
    }

    /**
     * The style the app itself is themed with, by name: the id has to be looked up in the loaded
     * apk's resources, and no id can be known at compile time here.
     */
    fun appThemeStyle(): Int =
        hostedResources.getIdentifier("AppTheme", "style", MANAGER_PACKAGE)

    private companion object {
        const val MANAGER_PACKAGE = "io.github.grachevskaya.asya"
    }
}
