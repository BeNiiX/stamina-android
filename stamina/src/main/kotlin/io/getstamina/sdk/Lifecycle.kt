// Cycle de vie de l'app sans dépendance : comptage des activités démarrées via
// Application.registerActivityLifecycleCallbacks. 0 → 1 = premier plan, 1 → 0 = arrière-plan.
// Référence : docs/13-SPEC-SDK.md §7.
package io.getstamina.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class LifecycleObserver(
    private val app: Application,
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    init { app.registerActivityLifecycleCallbacks(this) }

    fun stop() { app.unregisterActivityLifecycleCallbacks(this) }

    override fun onActivityStarted(activity: Activity) {
        started += 1
        if (started == 1) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        if (started == 0) onBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
