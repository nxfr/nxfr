# NXFR Design System — Bold Identity (v1.0)

NXFR is a privacy-first, peer-to-peer file transfer protocol. The visual language reflects **"banking-app calm"**, precision, and mathematical trust.

---

## 🎨 Color Palette Tokens & WCAG Contrast Matrix

### Primary & Accent Colors
| Token Name | Hex Code | Dark Surface Contrast | Light Surface Contrast | Intended Usage Context |
| :--- | :--- | :--- | :--- | :--- |
| `ElectricCyan` | `#00E5FF` | **12.4:1** (AA/AAA) | 1.4:1 (Fail) | Primary branding, glowing hero indicators, active transfers (Dark Mode) |
| `AccessibleCyan`| `#00838F` | 3.1:1 (Fail) | **5.2:1** (AA) | Primary buttons, active state highlights on light surfaces (Light Mode) |
| `MintGreen` | `#4ECDC4` | **10.2:1** (AA/AAA) | 1.8:1 (Fail) | Success states, completion checkmarks, verified pairing indicators (Dark) |
| `DarkSuccess` | `#2E7D32` | 3.5:1 (Fail) | **4.8:1** (AA) | Success text/badges on light surfaces (Light Mode) |
| `CoralRed` | `#FF6B6B` | **7.5:1** (AA/AAA) | 2.1:1 (Fail) | Cancel actions, transfer errors, security alerts (Dark Mode) |
| `LightError` | `#D32F2F` | 2.9:1 (Fail) | **5.4:1** (AA) | Error states and destructive actions on light surfaces (Light Mode) |

### Surface & Neutral Hierarchy
| Token Name | Hex Code | Intended Usage Context |
| :--- | :--- | :--- |
| `DeepSlate` | `#0F172A` | Primary app background (Dark Mode) |
| `WarmPaper` | `#F8F9FA` | Primary app background (Light Mode) |
| `DarkSurfaceVariant` | `#1E293B` | Card backgrounds, elevated sheets, input fields (Dark Mode) |
| `LightSurfaceVariant` | `#F1F5F9` | Card backgrounds, elevated sheets, input fields (Light Mode) |
| `DarkOnSurface` | `#E2E8F0` | High-emphasis primary text & headers (Dark Mode — **13.8:1** on `#0F172A`) |
| `LightOnSurface` | `#0F172A` | High-emphasis primary text & headers (Light Mode — **16.5:1** on `#F8F9FA`) |
| `DarkOnSurfaceVariant` | `#94A3B8` | Subtitle text, inactive icons, helper text (Dark Mode — **6.4:1** on `#0F172A`) |
| `LightOnSurfaceVariant`| `#475569` | Subtitle text, inactive icons, helper text (Light Mode — **7.1:1** on `#F8F9FA`) |
| `DarkOutline` | `#475569` | Card borders, dividers, disabled controls (Dark Mode) |
| `LightOutline` | `#CBD5E1` | Card borders, dividers, disabled controls (Light Mode) |

---

## 📐 Elevation & Surface Architecture

To preserve dark-mode legibility and slate restraint, NXFR relies on **outlined surface borders** rather than heavy shadows.

| Level | Elevation | Border Treatment | Context & Component Usage |
| :--- | :--- | :--- | :--- |
| `Level 0` | `0.dp` | None | Base screen background (`DeepSlate` / `WarmPaper`) |
| `Level 1` | `1.dp` / `0.dp` elev | 1.dp `OutlineVariant` | Primary Content Cards (Device Cards, Info Sheet, Settings Rows) |
| `Level 2` | `3.dp` elevation | 1.dp `Outline` | Interactive Floating Elements (FAB, Hovered Cards) |
| `Level 3` | `6.dp` elevation | None | Modal Overlays (`ConsentDialog`, `ModalBottomSheet`) |

---

## 🔤 Typography & Font Hierarchy

- **UI Typeface**: `Inter` (Static clean sans-serif)
- **Code / Cryptographic Typeface**: `FontFamily.Monospace` / `JetBrains Mono`

| Style Name | Size / Line-Height | Weight | Tracking | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `Display Large` | 32.sp / 40.sp | Bold (700) | -0.5.sp | Hero headers, radar title |
| `Headline Small` | 24.sp / 32.sp | SemiBold (600) | -0.2.sp | Section titles, screen headers |
| `Title Large` | 20.sp / 26.sp | SemiBold (600) | 0.sp | Card headers, device names |
| `Title Medium` | 16.sp / 22.sp | Medium (500) | 0.1.sp | Sub-headers, dialog titles |
| `Body Large` | 16.sp / 24.sp | Regular (400) | 0.15.sp | Primary readable text |
| `Body Medium` | 14.sp / 20.sp | Regular (400) | 0.25.sp | Secondary text, descriptions |
| `Label Small` | 11.sp / 16.sp | Medium (500) | 0.5.sp | Chips, captions, security notes |
| `Monospace Code` | 13.sp / 18.sp | Medium (500) | 0.sp | Device IDs, IP addresses, SAS codes, tokens |

---

## 🔲 Shape System

- **Small (`8.dp`)**: Chips, tooltips, small buttons, text input fields.
- **Medium (`12.dp`)**: Cards, list items, alert dialogs.
- **Large (`16.dp`)**: Hero containers, bottom sheets, full-width action cards.
- **Full (`999.dp`)**: Floating action buttons, avatar badges, radar pulse rings.
