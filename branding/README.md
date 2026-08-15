# NXFR Branding Assets

## Design System: The Beam (Bracket-Beam)

The NXFR production mark is a flat geometric mark representing peer-to-peer data transport:

1. **Two Mirrored Angular Brackets (`< >`)** — representing two independent nodes/peers.
2. **Horizontal Segmented Beam (`-- --`)** — representing the encrypted channel between peers.
3. **Central Packet Square (`■`)** — the single focal accent representing data in transit.

```
       <  - -  ■  - -  >
     [PEER]  [BEAM]  [PACKET]  [BEAM]  [PEER]
```

## Color Palette

| Token | Hex | Role | Usage |
| :--- | :--- | :--- | :--- |
| **Cockpit Obsidian** | `#0B0F17` | Canvas Base | Dark theme background |
| **Drafting Paper** | `#F8FAFC` | Structure | Brackets & beam lines (dark theme), light theme canvas |
| **Signal Cyan** | `#00E5FF` | Active Accent | Sole packet square accent in dark theme & active telemetry |
| **Accessible Cyan** | `#00838F` | Accent (Light) | Packet square accent on light theme surfaces |
| **Slate 400** | `#94A3B8` | Typography | Tagline & secondary metadata |

## Deliverables & Files

| File | Dimensions | Target Surface |
| :--- | :--- | :--- |
| `logo-icon.svg` | 512×512 | Dark theme primary mark (Cockpit Obsidian background) |
| `logo-icon-light.svg` | 512×512 | Light theme primary mark (Drafting Paper background) |
| `logo-mono.svg` | 512×512 | Single-color vector mark for print, terminal, and `currentColor` |
| `logo-full.svg` | 440×100 | Horizontal lockup: mark + Inter 800 wordmark + JetBrains Mono tagline |
| `logo-full-light.svg` | 440×100 | Light horizontal lockup |
| `favicon-32.svg` | 32×32 | Browser favicon at 32px |
| `favicon-16.svg` | 16×16 | Browser favicon at 16px (simplified `< ■ >` without small dashes) |
| `social-preview.svg` | 1280×640 | GitHub social preview & OpenGraph banner |
| `png/` | Various | Rasterized PNG exports at 512, 192, 48, 32, 16px |

## Android Adaptive Icon

- **Foreground:** `apps/android/app/src/main/res/drawable/ic_launcher_foreground.xml` (mark fitted inside the 72dp safe zone of a 108dp viewport)
- **Background:** `apps/android/app/src/main/res/drawable/ic_launcher_background.xml` (flat `#0B0F17`)
- **Mipmaps:** Pre-rendered fallback PNGs at `mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi` in `res/mipmap-*/`

## Exporting Raster PNGs

```bash
# Production icons
rsvg-convert -w 512 -h 512 branding/logo-icon.svg -o branding/png/logo-icon-512.png
rsvg-convert -w 192 -h 192 branding/logo-icon.svg -o branding/png/logo-icon-192.png
rsvg-convert -w 48  -h 48  branding/logo-icon.svg -o branding/png/logo-icon-48.png

# Favicons
rsvg-convert -w 32  -h 32  branding/favicon-32.svg -o branding/png/favicon-32.png
rsvg-convert -w 16  -h 16  branding/favicon-16.svg -o branding/png/favicon-16.png

# Social preview (1280x640)
rsvg-convert -w 1280 -h 640 branding/social-preview.svg -o branding/png/social-preview.png
```
