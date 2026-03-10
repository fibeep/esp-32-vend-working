/**
 * Root build configuration for the VMflow Vending App.
 *
 * This file declares the Gradle plugins used across the project without applying them,
 * allowing each module to opt in to the plugins it needs.
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
