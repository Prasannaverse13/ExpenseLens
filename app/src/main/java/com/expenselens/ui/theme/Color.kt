package com.expenselens.ui.theme

import androidx.compose.ui.graphics.Color

// =====================================================================
// ExpenseLens — Organic Spatiality palette
// Anchored on Deep Emerald, accented with Soft Clay Amber + Soft Sage.
// All UI surfaces float over a Cream Off-White "Grainient" canvas.
// =====================================================================

// -- Brand / Primary (Deep Emerald) -----------------------------------
val Emerald900 = Color(0xFF003329)   // primary
val OnEmerald  = Color(0xFFFFFFFF)   // on-primary
val Emerald800 = Color(0xFF1A4A3F)   // primary-container (used as gradient end)
val Emerald700 = Color(0xFF1F4F43)   // on-primary-fixed-variant
val Emerald600 = Color(0xFF38675B)   // surface-tint
val Emerald500 = Color(0xFF6FA593)   // gradient start
val Emerald400 = Color(0xFF89B9AA)   // on-primary-container
val Emerald300 = Color(0xFFA0D0C2)   // inverse-primary
val Emerald200 = Color(0xFFBBEDD)    // (typo guard - use 0xFFBBEDDD below)
val Emerald100 = Color(0xFFBBEDDD)   // primary-fixed

// -- Secondary (Soft Clay Amber) --------------------------------------
val Amber900 = Color(0xFF825429)     // secondary
val Amber800 = Color(0xFF794C22)     // on-secondary-container
val Amber700 = Color(0xFF673D14)     // on-secondary-fixed-variant
val Amber500 = Color(0xFFF7BA86)     // secondary-fixed-dim
val Amber400 = Color(0xFFFEBF8C)     // secondary-container
val Amber300 = Color(0xFFFFDCC2)     // secondary-fixed

// -- Tertiary (Sage — for category accents) ---------------------------
val Sage900 = Color(0xFF003328)      // tertiary
val Sage800 = Color(0xFF175041)      // on-tertiary-fixed-variant
val Sage700 = Color(0xFF114B3D)      // tertiary-container
val Sage400 = Color(0xFF83BAA7)      // on-tertiary-container
val Sage200 = Color(0xFF9AD2BE)      // tertiary-fixed-dim
val Sage100 = Color(0xFFB6EEDA)      // tertiary-fixed

// -- Neutral surfaces (Cream) -----------------------------------------
val Cream50  = Color(0xFFFCF9F4)     // surface / background
val Cream100 = Color(0xFFF6F3EE)     // surface-container-low
val Cream200 = Color(0xFFF0EDE9)     // surface-container
val Cream300 = Color(0xFFEBE8E3)     // surface-container-high
val Cream400 = Color(0xFFE5E2DD)     // surface-container-highest / surface-variant
val Cream500 = Color(0xFFDCDAD5)     // surface-dim
val Onyx     = Color(0xFF1C1C19)     // on-surface
val OnyxSoft = Color(0xFF404945)     // on-surface-variant
val OnyxDim  = Color(0xFF707975)     // outline
val OnyxLine = Color(0xFFC0C8C4)     // outline-variant
val InverseSurface     = Color(0xFF31302D)
val InverseOnSurface   = Color(0xFFF3F0EB)

// -- Accent blobs (Miromiro palette) ----------------------------------
val BlobPeach   = Color(0xFFFEBF8C)
val BlobMint    = Color(0xFFB6EEDA)
val BlobLavender = Color(0xFFD7CCE8)
val BlobSky     = Color(0xFFBFE0EE)

// -- Error -----------------------------------------------------------
val ErrorRed       = Color(0xFFBA1A1A)
val OnError        = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF93000A)

// -- Glass (translucent surfaces) -------------------------------------
val GlassLight  = Color(0x52FFFFFF)  // ~32% white
val GlassStrong = Color(0x66FFFFFF)  // ~40% white
val GlassEdge   = Color(0x80FFFFFF)  // 50% white inner border
val GlassInner  = Color(0x4DFFFFFF)  // 30% white inner border
