package xyz.vmflow.vending.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color palette for the VMflow Vending App.
 *
 * Defines all colors used in the Material3 theme, organized by their role
 * in the light and dark color schemes. The palette is based on a blue primary
 * color that conveys trust and technology.
 */

// ── Primary Colors ─────────────────────────────────────────────────────────

/** Primary brand color - vibrant blue for key actions and branding */
val VmflowPrimary = Color(0xFF2563EB)
/** Text/icon color on top of primary surfaces */
val VmflowOnPrimary = Color(0xFFFFFFFF)
/** Lighter primary variant for containers and backgrounds */
val VmflowPrimaryContainer = Color(0xFFD5E3FF)
/** Text/icon color on top of primary containers */
val VmflowOnPrimaryContainer = Color(0xFF001B3D)

// ── Secondary Colors ───────────────────────────────────────────────────────

/** Secondary color for less prominent elements */
val VmflowSecondary = Color(0xFF555F71)
/** Text/icon color on top of secondary surfaces */
val VmflowOnSecondary = Color(0xFFFFFFFF)
/** Secondary container for chips, pills, and secondary surfaces */
val VmflowSecondaryContainer = Color(0xFFD9E3F8)
/** Text/icon color on top of secondary containers */
val VmflowOnSecondaryContainer = Color(0xFF121C2B)

// ── Tertiary Colors ────────────────────────────────────────────────────────

/** Tertiary color for complementary accents */
val VmflowTertiary = Color(0xFF6E5676)
/** Text/icon color on top of tertiary surfaces */
val VmflowOnTertiary = Color(0xFFFFFFFF)
/** Tertiary container for accent backgrounds */
val VmflowTertiaryContainer = Color(0xFFF8D8FF)
/** Text/icon color on top of tertiary containers */
val VmflowOnTertiaryContainer = Color(0xFF28132F)

// ── Error Colors ───────────────────────────────────────────────────────────

/** Error color for destructive actions and error states */
val VmflowError = Color(0xFFBA1A1A)
/** Text/icon color on top of error surfaces */
val VmflowOnError = Color(0xFFFFFFFF)
/** Error container background */
val VmflowErrorContainer = Color(0xFFFFDAD6)
/** Text/icon color on top of error containers */
val VmflowOnErrorContainer = Color(0xFF410002)

// ── Neutral Colors ─────────────────────────────────────────────────────────

/** Main background color */
val VmflowBackground = Color(0xFFFAFBFF)
/** Text/icon color on the background */
val VmflowOnBackground = Color(0xFF1A1C20)
/** Surface color for cards, sheets, and dialogs */
val VmflowSurface = Color(0xFFFAFBFF)
/** Text/icon color on surfaces */
val VmflowOnSurface = Color(0xFF1A1C20)
/** Variant surface color for secondary surfaces */
val VmflowSurfaceVariant = Color(0xFFE0E2EC)
/** Text/icon color on variant surfaces */
val VmflowOnSurfaceVariant = Color(0xFF44474E)
/** Outline color for borders and dividers */
val VmflowOutline = Color(0xFF74777F)
/** Variant outline for subtle borders */
val VmflowOutlineVariant = Color(0xFFC4C6D0)

// ── Dark Theme Colors ──────────────────────────────────────────────────────

/** Dark theme primary */
val VmflowPrimaryDark = Color(0xFFA8C8FF)
/** Dark theme on-primary */
val VmflowOnPrimaryDark = Color(0xFF003063)
/** Dark theme primary container */
val VmflowPrimaryContainerDark = Color(0xFF00468C)
/** Dark theme on-primary-container */
val VmflowOnPrimaryContainerDark = Color(0xFFD5E3FF)

/** Dark theme background */
val VmflowBackgroundDark = Color(0xFF1A1C20)
/** Dark theme on-background */
val VmflowOnBackgroundDark = Color(0xFFE3E2E6)
/** Dark theme surface */
val VmflowSurfaceDark = Color(0xFF1A1C20)
/** Dark theme on-surface */
val VmflowOnSurfaceDark = Color(0xFFE3E2E6)

// ── Status Colors (used across themes) ─────────────────────────────────────

/** Green indicator for online/connected/success status */
val StatusOnline = Color(0xFF22C55E)
/** Red indicator for offline/disconnected/error status */
val StatusOffline = Color(0xFFEF4444)
/** Amber indicator for connecting/processing/warning status */
val StatusConnecting = Color(0xFFF59E0B)
