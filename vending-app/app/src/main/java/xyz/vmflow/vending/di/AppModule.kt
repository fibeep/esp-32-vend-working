package xyz.vmflow.vending.di

import android.content.Context
import android.content.SharedPreferences
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import xyz.vmflow.vending.data.remote.ApiClient
import xyz.vmflow.vending.data.repository.AuthRepository
import xyz.vmflow.vending.data.repository.DeviceRepository
import xyz.vmflow.vending.data.repository.SalesRepository
import xyz.vmflow.vending.data.repository.VendingRepository
import xyz.vmflow.vending.domain.payment.MockPaymentProvider
import xyz.vmflow.vending.domain.payment.PaymentProvider
import xyz.vmflow.vending.ui.auth.LoginViewModel
import xyz.vmflow.vending.ui.auth.RegisterViewModel
import xyz.vmflow.vending.ui.devices.DevicesViewModel
import xyz.vmflow.vending.ui.sales.SalesViewModel
import xyz.vmflow.vending.ui.vending.VendingViewModel

/**
 * Koin dependency injection module for the VMflow Vending App.
 *
 * Defines the full dependency graph including:
 * - **Infrastructure**: SharedPreferences, HTTP client
 * - **Repositories**: Auth, Device, Sales, Vending
 * - **Domain**: Payment provider (pluggable)
 * - **ViewModels**: One per screen, scoped to Compose navigation
 *
 * ## Dependency Graph
 * ```
 * SharedPreferences ──► AuthRepository ──► DeviceRepository
 *                                     ──► SalesRepository
 *                                     ──► VendingRepository
 * HttpClient ────────► All Repositories
 * PaymentProvider ───► VendingViewModel
 * ```
 */
val appModule = module {

    // ── Infrastructure ─────────────────────────────────────────────────────────

    /**
     * SharedPreferences for persisting auth tokens and app settings.
     * Uses a dedicated prefs file to avoid conflicts with other apps.
     */
    single<SharedPreferences> {
        androidContext().getSharedPreferences("vmflow_vending_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Ktor HTTP client configured for JSON API communication.
     * Single instance shared across all repositories.
     */
    single { ApiClient.create() }

    // ── Repositories ───────────────────────────────────────────────────────────

    /** Authentication repository for login, register, and token management */
    single { AuthRepository(httpClient = get(), prefs = get()) }

    /** Device CRUD repository for managing ESP32 embedded devices */
    single { DeviceRepository(httpClient = get(), authRepository = get()) }

    /** Sales query repository for transaction history */
    single { SalesRepository(httpClient = get(), authRepository = get()) }

    /** Vending credit request repository for the BLE payment flow */
    single { VendingRepository(httpClient = get(), authRepository = get()) }

    // ── Domain ─────────────────────────────────────────────────────────────────

    /**
     * Payment provider interface bound to the mock implementation.
     * Replace with a real payment provider (Stripe, Square, etc.) in production.
     */
    single<PaymentProvider> { MockPaymentProvider() }

    // ── ViewModels ─────────────────────────────────────────────────────────────

    /** Login screen ViewModel */
    viewModel { LoginViewModel(authRepository = get()) }

    /** Registration screen ViewModel */
    viewModel { RegisterViewModel(authRepository = get()) }

    /** Vending flow ViewModel (the core state machine) */
    viewModel { VendingViewModel(vendingRepository = get(), paymentProvider = get()) }

    /** Device management screen ViewModel */
    viewModel { DevicesViewModel(deviceRepository = get()) }

    /** Sales history screen ViewModel */
    viewModel { SalesViewModel(salesRepository = get()) }
}
