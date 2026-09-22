package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================================
// Brand Identity Palette
// ============================================================================
val RedPrimary = Color(0xFFDC2626)          // Deep Modern Crimson Red
val RedSecondary = Color(0xFFE11D48)        // Rose Accent Red
val RedTertiary = Color(0xFFF87171)         // Soft Coral Accent
val RedContainer = Color(0xFFFEF2F2)        // Soft Warm Crimson Tint Surface
val OnRedContainer = Color(0xFF991B1B)

val DarkRedPrimary = Color(0xFFEF4444)      // Vibrant Night Crimson
val DarkRedSecondary = Color(0xFFF43F5E)    // Rose Ruby Glow
val DarkRedContainer = Color(0xFF450A0A)    // Deep Velvet Crimson Card Surface
val OnDarkRedContainer = Color(0xFFFCA5A5)  // High-contrast Soft Rose Text

// Status Indicators (Legacy Constants for Compatibility)
val StatusAvailable = Color(0xFF10B981)
val StatusWaiting = Color(0xFFF59E0B)
val StatusOffline = Color(0xFFEF4444)
val StatusAssigned = Color(0xFF3B82F6)

val GateGreen = Color(0xFF10B981)
val GateGreenContainer = Color(0xFFDCFCE7)

val CartStatusMoving = Color(0xFF10B981)
val CartStatusHalted = Color(0xFFF59E0B)
val CartStatusOffline = Color(0xFFEF4444)

// ============================================================================
// Light Mode Surface Hierarchy
// ============================================================================
val SlateBackground = Color(0xFFF8FAFC)     // Clean subtle off-white background
val SlateSurface = Color(0xFFFFFFFF)        // Crisp Pure White Card Surface
val SlateSurfaceVariant = Color(0xFFF1F5F9) // Secondary card / Pill surface
val SlateSurfaceLevel3 = Color(0xFFE2E8F0)
val SlateTextPrimary = Color(0xFF0F172A)    // High-contrast Slate-900
val SlateTextSecondary = Color(0xFF475569)  // Slate-600
val SlateTextMuted = Color(0xFF94A3B8)      // Slate-400
val CardBorder = Color(0xFFE2E8F0)          // Subtle border

// ============================================================================
// Dark Mode Luxury Obsidian Surface Hierarchy
// ============================================================================
val DarkBackground = Color(0xFF0B0F19)      // Rich Midnight Obsidian Canvas
val DarkSurface = Color(0xFF131C2E)         // Elevated Deep Navy-Slate Surface
val DarkSurfaceVariant = Color(0xFF1E293B)  // Elevated Level-2 Surface (pills, chips)
val DarkSurfaceLevel3 = Color(0xFF24334D)   // Elevated Level-3 Interactive Surface
val DarkBorder = Color(0xFF1E293B)          // Crisp Glassmorphic Border
val DarkBorderAccent = Color(0xFF334155)    // Highlight Border
val DarkTextPrimary = Color(0xFFF8FAFC)     // High-contrast crisp white
val DarkTextSecondary = Color(0xFF94A3B8)   // Balanced slate-400 secondary text
val DarkTextMuted = Color(0xFF64748B)       // Muted slate-500 helper text

// ============================================================================
// Semantic Status Design Tokens
// ============================================================================
@Immutable
data class CampusStatusColors(
    val successContainer: Color,
    val onSuccessContainer: Color,
    val successBorder: Color,
    val successDot: Color,

    val warningContainer: Color,
    val onWarningContainer: Color,
    val warningBorder: Color,
    val warningDot: Color,

    val dangerContainer: Color,
    val onDangerContainer: Color,
    val dangerBorder: Color,
    val dangerDot: Color,

    val infoContainer: Color,
    val onInfoContainer: Color,
    val infoBorder: Color,
    val infoDot: Color,

    val neutralContainer: Color,
    val onNeutralContainer: Color,
    val neutralBorder: Color,
    val neutralDot: Color,

    val surfaceLevel1: Color,
    val surfaceLevel2: Color,
    val surfaceLevel3: Color,
    val borderSubtle: Color,
    val borderAccent: Color
)

val LightCampusStatusColors = CampusStatusColors(
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF15803D),
    successBorder = Color(0xFF86EFAC),
    successDot = Color(0xFF16A34A),

    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFFB45309),
    warningBorder = Color(0xFFFCD34D),
    warningDot = Color(0xFFD97706),

    dangerContainer = Color(0xFFFEF2F2),
    onDangerContainer = Color(0xFF991B1B),
    dangerBorder = Color(0xFFFCA5A5),
    dangerDot = Color(0xFFDC2626),

    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF0369A1),
    infoBorder = Color(0xFF7DD3FC),
    infoDot = Color(0xFF0284C7),

    neutralContainer = Color(0xFFF1F5F9),
    onNeutralContainer = Color(0xFF475569),
    neutralBorder = Color(0xFFCBD5E1),
    neutralDot = Color(0xFF94A3B8),

    surfaceLevel1 = SlateSurface,
    surfaceLevel2 = SlateSurfaceVariant,
    surfaceLevel3 = SlateSurfaceLevel3,
    borderSubtle = CardBorder,
    borderAccent = Color(0xFFCBD5E1)
)

val DarkCampusStatusColors = CampusStatusColors(
    successContainer = Color(0xFF064E3B),
    onSuccessContainer = Color(0xFF6EE7B7),
    successBorder = Color(0xFF059669),
    successDot = Color(0xFF10B981),

    warningContainer = Color(0xFF451A03),
    onWarningContainer = Color(0xFFFDE68A),
    warningBorder = Color(0xFFB45309),
    warningDot = Color(0xFFF59E0B),

    dangerContainer = Color(0xFF450A0A),
    onDangerContainer = Color(0xFFFCA5A5),
    dangerBorder = Color(0xFFB91C1C),
    dangerDot = Color(0xFFEF4444),

    infoContainer = Color(0xFF082F49),
    onInfoContainer = Color(0xFF7DD3FC),
    infoBorder = Color(0xFF0284C7),
    infoDot = Color(0xFF38BDF8),

    neutralContainer = Color(0xFF1E293B),
    onNeutralContainer = Color(0xFFCBD5E1),
    neutralBorder = Color(0xFF334155),
    neutralDot = Color(0xFF94A3B8),

    surfaceLevel1 = DarkSurface,
    surfaceLevel2 = DarkSurfaceVariant,
    surfaceLevel3 = DarkSurfaceLevel3,
    borderSubtle = DarkBorder,
    borderAccent = DarkBorderAccent
)

val LocalCampusStatusColors = staticCompositionLocalOf { LightCampusStatusColors }
