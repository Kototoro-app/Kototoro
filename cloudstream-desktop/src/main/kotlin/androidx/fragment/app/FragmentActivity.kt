package androidx.fragment.app

import android.app.Activity

/**
 * AndroidX's fragment activity, which the Windows compatibility runtime lacks. Cloudstream plugins cast the context
 * they load with to `AppCompatActivity` (CNC Verse keeps it for its settings dialog), so the class has to exist for
 * the cast to succeed; Windows draws no plugin screens, so it carries nothing else.
 */
open class FragmentActivity : Activity()
