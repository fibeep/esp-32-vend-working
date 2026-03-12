package xyz.vmflow.setup

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import xyz.vmflow.setup.data.ApiClient
import xyz.vmflow.setup.data.AuthRepository
import xyz.vmflow.setup.data.DeviceRepository
import xyz.vmflow.setup.ui.login.LoginViewModel
import xyz.vmflow.setup.ui.setup.SetupViewModel

class SetupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@SetupApp)
            modules(appModule)
        }
    }
}

val appModule = module {
    single { ApiClient.create() }
    single {
        val prefs = get<android.content.Context>()
            .getSharedPreferences("auth", android.content.Context.MODE_PRIVATE)
        AuthRepository(get(), prefs)
    }
    single { DeviceRepository(get(), get()) }
    factory { LoginViewModel(get()) }
    factory { SetupViewModel(get()) }
}
