package cl.filamentbox.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class FilamentBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseConfig.isConfigured && FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey(FirebaseConfig.API_KEY)
                .setApplicationId(FirebaseConfig.APPLICATION_ID)
                .setProjectId(FirebaseConfig.PROJECT_ID)
                .setDatabaseUrl(FirebaseConfig.DATABASE_URL)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            // Must be set before obtaining the first FirebaseDatabase reference.
            com.google.firebase.database.FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            com.google.firebase.database.FirebaseDatabase.getInstance().setPersistenceCacheSizeBytes(20L * 1024 * 1024)
        }
    }
}
