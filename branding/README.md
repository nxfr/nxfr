# NXFR Branding Assets

## Design Philosophy

The NXFR mark is a bold geometric **"N"** composed of three elements:

1. **Two vertical bars** — representing two devices (nodes) on a LAN.
2. **A diagonal stroke** connecting them — representing the encrypted data transfer.
3. **Bidirectional arrow tips** extending beyond the bars — representing peer-to-peer flow in both directions.

Together, the mark reads as a stylized letter **N** (for *Nearby*) with embedded transfer semantics. The arrow tips subtly convey that NXFR is a *two-way* protocol — either device can be sender or receiver.

## Color Palette

| Token         | Hex       | Usage                           |
|---------------|-----------|---------------------------------|
| Electric Cyan | `#00E5FF` | Primary brand, logo, accents    |
| Deep Slate    | `#0F172A` | Dark backgrounds, favicon bg    |
| Pure White    | `#FFFFFF` | Light backgrounds, dark-mode text |
| Slate 400     | `#94A3B8` | Subtitle text, secondary info   |
| Slate 700     | `#1E293B` | Grid lines, muted decorations   |

## Files

| File                 | Dimensions    | Usage                                |
|----------------------|---------------|--------------------------------------|
| `logo-icon.svg`      | 100×100       | App icon, favicons, avatar           |
| `logo-full.svg`      | 320×100       | README header, nav bars, docs        |
| `logo-full-light.svg`| 320×100       | README header, nav bars, docs (light)|
| `favicon.svg`        | 100×100       | Browser tab icon (dark bg + bold N)  |
| `social-preview.svg` | 1280×640      | GitHub social preview, Open Graph    |
| `png/`               | Various       | Exported raster graphics for web     |

## Light / Dark Usage

| Context | Logo File | Mark Color | Wordmark Color |
|---------|-----------|------------|----------------|
| Dark backgrounds, docs, GitHub | `logo-full.svg` | Electric Cyan `#00E5FF` | Electric Cyan `#00E5FF` |
| Light backgrounds, print, email | `logo-full-light.svg` | Cyan-700 `#0E7490` | Deep Slate `#0F172A` |
| Theme-adaptive (CSS) | `logo-icon.svg` with `currentColor` | Inherits text color | — |

## Theme Adaptation

All logo SVGs use `fill="#00E5FF"` (Electric Cyan) which pops on both light and dark backgrounds. To make the logo automatically adapt to the surrounding theme, replace:

```diff
- fill="#00E5FF"
+ fill="currentColor"
```

The logo will then inherit the text color of its container.

## Converting SVGs to PNGs

### Using `rsvg-convert` (librsvg — recommended)

```bash
# Install (Debian/Ubuntu)
sudo apt install librsvg2-bin

# Icon at various sizes
rsvg-convert -w 512 -h 512 branding/logo-icon.svg -o branding/logo-icon-512.png
rsvg-convert -w 192 -h 192 branding/logo-icon.svg -o branding/logo-icon-192.png
rsvg-convert -w 48  -h 48  branding/logo-icon.svg -o branding/logo-icon-48.png

# Favicon
rsvg-convert -w 32  -h 32  branding/favicon.svg -o branding/favicon-32.png
rsvg-convert -w 16  -h 16  branding/favicon.svg -o branding/favicon-16.png

# Social preview (GitHub wants PNG/JPEG, 1280x640)
rsvg-convert -w 1280 -h 640 branding/social-preview.svg -o branding/social-preview.png

# Full logo for README
rsvg-convert -w 600 branding/logo-full.svg -o branding/logo-full.png
```

### Using Inkscape

```bash
# Install (Debian/Ubuntu)
sudo apt install inkscape

# Convert any SVG
inkscape branding/logo-icon.svg --export-type=png --export-width=512 --export-filename=branding/logo-icon-512.png
```

### Android Launcher Icon

For Android adaptive icons, you need a 108dp (432px at xxxhdpi) foreground layer:

```bash
rsvg-convert -w 432 -h 432 branding/logo-icon.svg -o app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png
```

Then set the adaptive icon background to `#0F172A` (Deep Slate) in `ic_launcher.xml`:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
```

## Guidelines

- **Do not** add gradients, shadows, or 3D effects to the mark.
- **Do not** rotate, stretch, or distort the mark.
- **Minimum clear space:** 25% of the mark's height on all sides.
- **Minimum size:** 16×16px for the icon, 120×38px for the full logo.
- The Electric Cyan mark should always be used on backgrounds with sufficient contrast (WCAG AA: at least 3:1 for large text/icons).
