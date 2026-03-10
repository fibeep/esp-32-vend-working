# Architecture

The VMflow Vending App follows a clean MVVM (Model-View-ViewModel) architecture with clear separation of concerns across four layers.

## Layer Overview

```
┌────────────────────────────────────────────────────────┐
│                    UI Layer                             │
│  Compose Screens + ViewModels + StateFlow              │
│  (LoginScreen, VendingScreen, DevicesScreen, Sales)    │
├────────────────────────────────────────────────────────┤
│                  Domain Layer                           │
│  Models + Payment Provider Interface                   │
│  (Device, Sale, VendRequest, PaymentProvider)          │
├────────────────────────────────────────────────────────┤
│                   Data Layer                            │
│  Repositories + API Client                             │
│  (AuthRepo, DeviceRepo, SalesRepo, VendingRepo)       │
├────────────────────────────────────────────────────────┤
│              Infrastructure Layer                       │
│  BLE (Kable) + HTTP (Ktor) + Storage (SharedPrefs)     │
│  (BleManager, XorCrypto, ApiClient)                    │
└────────────────────────────────────────────────────────┘
```

## UI Layer

### Screens and ViewModels

Each screen is a pair: a `@Composable` function and a `ViewModel` that exposes a `StateFlow` of the screen's UI state.

| Screen | ViewModel | State Class | Description |
|--------|-----------|-------------|-------------|
| LoginScreen | LoginViewModel | LoginUiState | Email/password auth |
| RegisterScreen | RegisterViewModel | RegisterUiState | Account creation |
| VendingScreen | VendingViewModel | VendingState (sealed) | Core vending flow |
| DevicesScreen | DevicesViewModel | DevicesUiState | Device management |
| SalesScreen | SalesViewModel | SalesUiState | Transaction history |

### Navigation

The app uses Jetpack Compose Navigation with a single `NavHost`:

```
Login ──┐
        ├──► Vending (Tab 1)
Register┘    Devices (Tab 2)
             Sales   (Tab 3)
```

- Auth screens have no bottom navigation bar
- Main screens share a bottom navigation bar via `MainScaffold`
- Tab state is preserved when switching between tabs

### State Management Pattern

Every ViewModel follows the same pattern:

```kotlin
class SomeViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SomeUiState())
    val uiState: StateFlow<SomeUiState> = _uiState.asStateFlow()

    fun onAction() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.doWork()
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                data = result
            )
        }
    }
}
```

The VendingViewModel is special -- it uses a sealed class (`VendingState`) instead of a data class, because its states are fundamentally different UI layouts rather than variations of the same layout.

## Domain Layer

### Models

- **Device**: Maps to the `embedded` Supabase table. Represents a registered ESP32.
- **Sale**: Maps to the `sales` Supabase table. Represents a vending transaction.
- **VendRequest**: Decoded BLE payload data (price, item number, timestamp).

### Payment Provider

The `PaymentProvider` interface enables pluggable payment backends:

```
PaymentProvider (interface)
    ├── MockPaymentProvider (always succeeds, for dev/test)
    ├── StripePaymentProvider (future)
    └── SquarePaymentProvider (future)
```

## Data Layer

### Repositories

Each repository encapsulates a specific data concern:

| Repository | Data Source | Operations |
|-----------|------------|------------|
| AuthRepository | Supabase GoTrue + SharedPrefs | Login, register, token refresh, logout |
| DeviceRepository | Supabase PostgREST | List devices, register device |
| SalesRepository | Supabase PostgREST | List sales with device joins |
| VendingRepository | Supabase Edge Functions | Credit request/approval flow |

All repositories share a token refresh pattern:

```
executeWithRetry {
    // Make API call
    // If 401 -> refresh token -> retry once
}
```

### API Client

The `ApiClient` provides a configured Ktor `HttpClient` with:
- OkHttp engine (Android-optimized)
- JSON content negotiation (kotlinx.serialization)
- Request/response logging
- Default JSON content type

## Infrastructure Layer

### BLE (Kable)

The `BleManager` wraps JuulLabs Kable for coroutine-native BLE:

```
BleManager
    ├── scanForDevices() -> Flow<BleDevice>
    ├── scanForUnconfiguredDevices() -> Flow<BleDevice>
    ├── connect(device)
    ├── startSession()           // Write 0x02
    ├── writePayload(bytes)      // Write approval
    ├── closeSession()           // Write 0x04
    ├── setSubdomain(string)     // Write 0x00 + data
    ├── setPasskey(string)       // Write 0x01 + data
    ├── setWifiSsid(string)      // Write 0x06 + data
    ├── setWifiPassword(string)  // Write 0x07 + data
    ├── observeNotifications() -> Flow<ByteArray>
    └── disconnect()
```

### XOR Crypto

The `XorCrypto` object provides symmetric encryption matching the ESP32 firmware:

```
XorCrypto
    ├── decode(payload, passkey) -> VendRequest?
    └── encode(command, price, item, passkey) -> ByteArray
```

## Dependency Injection

Koin modules defined in `AppModule.kt`:

```
appModule
    ├── SharedPreferences (singleton)
    ├── HttpClient (singleton)
    ├── AuthRepository (singleton)
    ├── DeviceRepository (singleton)
    ├── SalesRepository (singleton)
    ├── VendingRepository (singleton)
    ├── PaymentProvider (singleton, bound to MockPaymentProvider)
    ├── LoginViewModel (viewModel)
    ├── RegisterViewModel (viewModel)
    ├── VendingViewModel (viewModel)
    ├── DevicesViewModel (viewModel)
    └── SalesViewModel (viewModel)
```

## Data Flow

### Vending Purchase Flow

```
User taps "Scan" in VendingScreen
    └── VendingViewModel.startScan()
        └── BleManager.scanForDevices()
            └── Flow<BleDevice> -> VendingState.DevicesFound

User taps device
    └── VendingViewModel.connectToDevice()
        └── BleManager.connect() + startSession()
            └── VendingState.WaitingForSelection

User selects product on machine
    └── ESP32 sends 0x0A notification
        └── BleManager.observeNotifications()
            └── VendingViewModel.handleVendRequest()
                ├── PaymentProvider.processPayment()
                ├── VendingRepository.requestCredit() -> Backend
                └── BleManager.writePayload(approval)
                    └── VendingState.Dispensing

ESP32 sends 0x0B (success) or 0x0C (failure)
    └── VendingState.Success or VendingState.Failure

ESP32 sends 0x0D (session complete)
    └── VendingState.SessionComplete
        └── BleManager.disconnect()
```

### Authentication Flow

```
User enters credentials in LoginScreen
    └── LoginViewModel.login()
        └── AuthRepository.login()
            ├── POST /auth/v1/token -> Supabase GoTrue
            ├── Store tokens in SharedPreferences
            └── LoginUiState.isSuccess = true
                └── Navigate to VendingScreen
```
