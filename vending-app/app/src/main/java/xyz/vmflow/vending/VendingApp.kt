package xyz.vmflow.vending

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import xyz.vmflow.vending.di.appModule

/**
 * Application class for the VMflow Vending App.
 *
 * Initializes the Koin dependency injection framework at application startup.
 * This is the entry point for the entire dependency graph, ensuring that all
 * singletons (HTTP client, repositories, etc.) are created and available
 * before any Activity or ViewModel accesses them.
 *
 * Registered in AndroidManifest.xml as `android:name=".VendingApp"`.
 */
class VendingApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Koin DI framework with the app module
        startKoin {
            // Use Android logger for Koin debug output
            androidLogger(Level.INFO)

            // Provide the Android application context to Koin
            androidContext(this@VendingApp)

            // Load the dependency module definitions
            modules(appModule)
        }
    }
}
