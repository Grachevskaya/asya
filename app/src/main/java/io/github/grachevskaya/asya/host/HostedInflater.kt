package io.github.grachevskaya.asya.host

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View

/**
 * An inflater seated on the activity that owns it.
 *
 * Two things have to be true of the inflater the manager's screens are built with, and they pull in
opposite directions. Its resources have to come from the apk, which is what the hosted context
 * carries. And the context every view it creates has to lead back to the activity, because that is
 * what the tree is searched through for the FragmentActivity a FragmentContainerView insists on
 * finding above itself. A context cannot be both -- the hosted context's own base is the host -- but
 * an inflater can: this one is built on the activity, and the activity's resources are the hosted
 * ones, so the ids resolve in the apk while the views still see their activity.
 *
 * Cloning the host's inflater was the obvious way to do this, and on some builds it does not work:
 * cloneInContext answers with an inflater still seated where the original one was.
 *
 * The prefixes are the ones the platform's own inflater knows, so a layout written with plain widget
 * names inflates here exactly as it does anywhere else.
 */
class HostedInflater(context: Context) : LayoutInflater(context) {

    /** A copy on another context: what this class is for, said again for the next one. */
    override fun cloneInContext(newContext: Context): LayoutInflater = HostedInflater(newContext)

    override fun onCreateView(name: String, attrs: AttributeSet): View? {
        for (prefix in PREFIXES) {
            try {
                return createView(name, prefix, attrs)
            } catch (ignored: ClassNotFoundException) {
                // the next prefix, or the platform's own answer below
            }
        }

        return super.onCreateView(name, attrs)
    }

    private companion object {
        val PREFIXES = arrayOf("android.widget.", "android.webkit.", "android.app.")
    }
}
