package com.nxfr.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * NXFR Custom Instrument Deck UI Icon Set.
 * 
 * Aesthetic: Avionics cockpit telemetry, 1.5dp stroke, sharp miter corners,
 * maximum legibility at compact display scales.
 */
object NxfrIcons {

    private inline fun buildIcon(
        name: String,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    /** 1. RECEIVE — Tray with incoming telemetry vector */
    val Receive: ImageVector by lazy {
        buildIcon("NxfrIcons.Receive") {
            // Receiver station tray
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4f, 15f)
                lineTo(4f, 20f)
                lineTo(20f, 20f)
                lineTo(20f, 15f)
            }
            // Incoming beam vector & arrowhead
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 4f)
                lineTo(12f, 15f)
                moveTo(7f, 10f)
                lineTo(12f, 15f)
                lineTo(17f, 10f)
            }
        }
    }

    /** 2. SEND — High-velocity transmission telemetry dart */
    val Send: ImageVector by lazy {
        buildIcon("NxfrIcons.Send") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(20f, 4f)
                lineTo(3f, 11f)
                lineTo(10f, 14f)
                lineTo(13f, 21f)
                close()
                moveTo(10f, 14f)
                lineTo(20f, 4f)
            }
        }
    }

    /** 3. HISTORY — Telemetry chronometer */
    val History: ImageVector by lazy {
        buildIcon("NxfrIcons.History") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                // Circle centered at (12, 12) r=8
                moveTo(12f, 4f)
                curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
                curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
                curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
                curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
                close()
                // Chronometer hands
                moveTo(12f, 7f)
                lineTo(12f, 12f)
                lineTo(16f, 14f)
            }
        }
    }

    /** 4. SETTINGS — Avionics calibration module */
    val Settings: ImageVector by lazy {
        buildIcon("NxfrIcons.Settings") {
            // Outer enclosure with 2dp corner radius
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5f, 3f)
                lineTo(19f, 3f)
                lineTo(21f, 5f)
                lineTo(21f, 19f)
                lineTo(19f, 21f)
                lineTo(5f, 21f)
                lineTo(3f, 19f)
                lineTo(3f, 5f)
                close()
            }
            // Central core reticle
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(12f, 9f)
                curveTo(13.66f, 9f, 15f, 10.34f, 15f, 12f)
                curveTo(15f, 13.66f, 13.66f, 15f, 12f, 15f)
                curveTo(10.34f, 15f, 9f, 13.66f, 9f, 12f)
                curveTo(9f, 10.34f, 10.34f, 9f, 12f, 9f)
                close()
                // Crosshair pins
                moveTo(12f, 3f)
                lineTo(12f, 6f)
                moveTo(12f, 18f)
                lineTo(12f, 21f)
                moveTo(3f, 12f)
                lineTo(6f, 12f)
                moveTo(18f, 12f)
                lineTo(21f, 12f)
            }
        }
    }

    /** 5. QR-SCAN — Optical rangefinder frame */
    val QrScan: ImageVector by lazy {
        buildIcon("NxfrIcons.QrScan") {
            // 4 Corner brackets
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(3f, 8f); lineTo(3f, 3f); lineTo(8f, 3f)
                moveTo(16f, 3f); lineTo(21f, 3f); lineTo(21f, 8f)
                moveTo(21f, 16f); lineTo(21f, 21f); lineTo(16f, 21f)
                moveTo(8f, 21f); lineTo(3f, 21f); lineTo(3f, 16f)
            }
            // Center target bounds
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(7f, 7f)
                lineTo(17f, 7f)
                lineTo(17f, 17f)
                lineTo(7f, 17f)
                close()
            }
            // Solid telemetry center reticle
            path(fill = SolidColor(Color.White)) {
                moveTo(10f, 10f)
                lineTo(14f, 10f)
                lineTo(14f, 14f)
                lineTo(10f, 14f)
                close()
            }
        }
    }

    /** 6. WEB-LINK — Direct mesh interconnect network */
    val WebLink: ImageVector by lazy {
        buildIcon("NxfrIcons.WebLink") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                // Outer circle
                moveTo(12f, 4f)
                curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
                curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
                curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
                curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
                close()
                // Horizontal line
                moveTo(4f, 12f)
                lineTo(20f, 12f)
                // Center elliptical longitude
                moveTo(12f, 4f)
                curveTo(15f, 7f, 15f, 17f, 12f, 20f)
                curveTo(9f, 17f, 9f, 7f, 12f, 4f)
                close()
            }
        }
    }

    /** 7. DIAGNOSTICS — Cockpit oscilloscope telemetry */
    val Diagnostics: ImageVector by lazy {
        buildIcon("NxfrIcons.Diagnostics") {
            // Oscilloscope border
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(3f, 20f)
                close()
            }
            // Signal waveform
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5f, 13f)
                lineTo(8f, 13f)
                lineTo(10.5f, 8f)
                lineTo(13.5f, 16.5f)
                lineTo(16f, 10.5f)
                lineTo(17.5f, 13f)
                lineTo(19f, 13f)
            }
        }
    }

    /** 8. FILE — Standard data payload block */
    val File: ImageVector by lazy {
        buildIcon("NxfrIcons.File") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(5f, 3f)
                lineTo(14f, 3f)
                lineTo(19f, 8f)
                lineTo(19f, 21f)
                lineTo(5f, 21f)
                close()
                // Chamfer fold
                moveTo(14f, 3f)
                lineTo(14f, 8f)
                lineTo(19f, 8f)
            }
        }
    }

    /** 9. FOLDER — Directory filesystem tree container */
    val Folder: ImageVector by lazy {
        buildIcon("NxfrIcons.Folder") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(3f, 5f)
                lineTo(9f, 5f)
                lineTo(11f, 8f)
                lineTo(21f, 8f)
                lineTo(21f, 19f)
                lineTo(3f, 19f)
                close()
            }
        }
    }

    /** 10. CONTACT — Verified cryptographic peer station */
    val Contact: ImageVector by lazy {
        buildIcon("NxfrIcons.Contact") {
            // Identity module boundary
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
            }
            // Peer avatar head & shoulders
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                // Head
                moveTo(12f, 7f)
                curveTo(13.66f, 7f, 15f, 8.34f, 15f, 10f)
                curveTo(15f, 11.66f, 13.66f, 13f, 12f, 13f)
                curveTo(10.34f, 13f, 9f, 11.66f, 9f, 10f)
                curveTo(9f, 8.34f, 10.34f, 7f, 12f, 7f)
                close()
                // Torso
                moveTo(7f, 17f)
                curveTo(7f, 14.5f, 9.5f, 14.5f, 12f, 14.5f)
                curveTo(14.5f, 14.5f, 17f, 14.5f, 17f, 17f)
            }
        }
    }

    /** 11. APP — Application executable module grid */
    val App: ImageVector by lazy {
        buildIcon("NxfrIcons.App") {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                // Top-left block
                moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
                // Top-right block
                moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
                // Bottom-left block
                moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
                // Bottom-right block
                moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
            }
        }
    }

    /** 12. MEDIA — Streamable audio/video payload */
    val Media: ImageVector by lazy {
        buildIcon("NxfrIcons.Media") {
            // Viewport display frame
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Square,
                strokeLineJoin = StrokeJoin.Miter
            ) {
                moveTo(3f, 4f)
                lineTo(21f, 4f)
                lineTo(21f, 20f)
                lineTo(3f, 20f)
                close()
            }
            // Stream play glyph
            path(fill = SolidColor(Color.White)) {
                moveTo(10f, 8f)
                lineTo(16f, 12f)
                lineTo(10f, 16f)
                close()
            }
        }
    }
}
