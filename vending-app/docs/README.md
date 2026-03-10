# VMflow Vending App

A modern Android application for cashless vending machine payments via BLE, built with Kotlin, Jetpack Compose, and MVVM architecture.

## Prerequisites

- **Android Studio** Ladybug (2024.2.1) or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK 35** (API level 35)
- **Kotlin 2.1.0**
- An Android device or emulator with **BLE support** (API 26+)

## Quick Start

1. Clone the repository and navigate to the app directory:
   ```bash
   cd vending-app
   ```

2. Generate the Gradle wrapper (if not already present):
   ```bash
   gradle wrapper --gradle-version 8.9
   ```

3. Build the project:
   ```bash
   ./gradlew assembleDebug
   ```

4. Run unit tests:
   ```bash
   ./gradlew test
   ```

5. Install on a connected device:
   ```bash
   ./gradlew installDebug
   ```

## Project Structure

```
vending-app/
├── app/src/main/java/xyz/vmflow/vending/
│   ├── VendingApp.kt                 # Application class, Koin init
│   ├── di/AppModule.kt               # Dependency injection definitions
│   ├── bluetooth/                     # BLE communication layer
│   │   ├── BleConstants.kt           # UUIDs, command bytes
│   │   ├── BleManager.kt             # Kable BLE wrapper
│   │   └── XorCrypto.kt              # XOR encode/decode
│   ├── data/                          # Data layer
│   │   ├── remote/ApiClient.kt       # HTTP client config
│   │   └── repository/               # Repository implementations
│   ├── domain/                        # Domain layer
│   │   ├── model/                     # Data classes
│   │   └── payment/                   # Payment provider interface
│   └── ui/                            # Presentation layer
│       ├── theme/                     # Material3 theme
│       ├── navigation/NavGraph.kt     # Compose navigation
│       ├── auth/                      # Login/Register screens
│       ├── vending/                   # Core vending flow
│       ├── devices/                   # Device management
│       └── sales/                     # Sales history
├── app/src/test/                      # Unit tests
└── docs/                              # Documentation
```

## Technology Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| Language | Kotlin 2.1.0 | Primary language |
| UI | Jetpack Compose + Material3 | Declarative UI framework |
| Architecture | MVVM + StateFlow | Reactive state management |
| BLE | Kable 0.33.0 (JuulLabs) | Coroutine-native BLE |
| HTTP | Ktor 3.0.3 + OkHttp | Backend API communication |
| DI | Koin 4.0.1 | Dependency injection |
| Auth/DB | Supabase Kotlin SDK 3.0.3 | Authentication and database |
| Testing | JUnit 4 + MockK + Turbine | Unit testing |

## Configuration

### Backend URL

The app connects to a configurable backend. Edit `ApiClient.kt`:

```kotlin
object ApiConstants {
    const val BASE_URL = "https://api.panamavendingmachines.com"
    const val SUPABASE_URL = "https://supabase.panamavendingmachines.com"
}
```

### BLE UUIDs

BLE service and characteristic UUIDs are defined in `BleConstants.kt` and must match the ESP32 firmware configuration.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed architecture documentation including the MVVM layers, dependency graph, and data flow.

## BLE Protocol

See [BLE_FLOW.md](BLE_FLOW.md) for the complete BLE communication protocol documentation including the XOR encryption scheme and vending flow sequence.

## Testing

The project includes three test suites:

- **XorCryptoTest**: Verifies XOR encoding/decoding with known test vectors, checksum validation, and roundtrip integrity
- **VendingViewModelTest**: Tests all state machine transitions and payment provider integration
- **AuthRepositoryTest**: Tests token storage, retrieval, logout, and DTO serialization

Run all tests:
```bash
./gradlew test
```

## Permissions

The app requires the following Android permissions:

| Permission | Purpose |
|-----------|---------|
| BLUETOOTH_SCAN | Discover nearby BLE devices |
| BLUETOOTH_CONNECT | Connect to BLE devices |
| ACCESS_FINE_LOCATION | BLE scanning (pre-Android 12) + GPS for sales |
| INTERNET | Backend API communication |
| ACCESS_WIFI_STATE | WiFi scanning for device provisioning |
| CHANGE_WIFI_STATE | WiFi configuration during provisioning |
