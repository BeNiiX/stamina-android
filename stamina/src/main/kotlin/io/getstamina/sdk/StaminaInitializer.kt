// Démarrage sans code : si l'app déclare <meta-data android:name="io.getstamina.API_KEY" …/> dans son
// manifest, androidx.startup appelle cet initializer au lancement du process et le SDK se configure seul.
// Sans meta-data : aucun effet, Stamina.configure() reste la voie normale.
package io.getstamina.sdk

import android.content.Context
import android.content.pm.PackageManager
import androidx.startup.Initializer

class StaminaInitializer : Initializer<Stamina> {
    override fun create(context: Context): Stamina {
        try {
            val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val apiKey = info.metaData?.getString(META_API_KEY)
            if (!apiKey.isNullOrBlank()) Stamina.configure(context, apiKey)
        } catch (e: Throwable) {
            Log.warn("StaminaInitializer: lecture du manifest impossible ($e)")
        }
        return Stamina
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object { const val META_API_KEY = "io.getstamina.API_KEY" }
}
