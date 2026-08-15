# NXFR Design System — Instrument Deck

This document defines the visual design system, color tokens, typography hierarchy, and interactive component specifications for NXFR user interfaces.

---

## Design Principles

1. **Structural Clarity**: Interfaces prioritize angular, high-contrast layouts over decorative cards.
2. **Telemetry Visibility**: Protocol properties (TLS 1.3 encryption, socket parameters, chunk verification progress, node identifiers) are rendered in tabular monospace type.
3. **Signal Cyan**: Cyan (`#00E5FF`) is used exclusively to denote active transmission and energized network states. Inactive surfaces remain neutral slate.
4. **The Beam**: File transfers are visualized as a linear directional transmission wire between source and target nodes with real-time packet flow indicators.

---

## Color Tokens

### Dark Mode (Default)
| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `DeckDark` | `#0B0F17` | Root background |
| `DeckSurface` | `#131B26` | Structural panel surface |
| `DeckSurfaceVariant` | `#1A2332` | Elevated module card |
| `DeckSurfaceContainer`| `#0F151F` | Recessed well |
| `DeckGridLine` | `#1E293B` | Structural hairline grid (0.5–1dp) |
| `DeckGridLineBright` | `#334155` | Active control outline |
| `DeckTextPrimary` | `#F1F5F9` | High-contrast readout |
| `DeckTextSecondary` | `#94A3B8` | Telemetry label |
| `DeckTextDim` | `#64748B` | Inactive label |

### Signal Tokens
| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `SignalBeam` | `#00E5FF` | Active transmission cyan |
| `SignalBeamGlow` | `#3300E5FF` | 20% alpha outer beam glow |
| `SignalStandby` | `#475569` | Dormant wire slate |
| `SignalAlert` | `#FF3366` | Breaker trip / Error coral |
| `SignalSuccess` | `#00E676` | Verification green |
| `SignalWarning` | `#FFFFB300`| Action required amber |

### Light Mode
| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `DeckPaper` | `#F8FAFC` | Root background |
| `DeckPaperSurface` | `#FFFFFF` | Structural panel |
| `DeckPaperGridLine` | `#CBD5E1` | Grid border |
| `DeckPaperTextPrimary`| `#0F172A` | Primary readout |

### OLED Mode
Surfaces clamp to `#000000` with 1dp `DeckGridLineBright` outlines for battery preservation on AMOLED panels.

---

## Typography

- **Interface Typeface**: `Inter` (Display, Headlines, Titles, Body)
- **Telemetry Typeface**: `FontFamily.Monospace` (Node IDs, SAS authentication codes, socket parameters, transfer statistics)
- **Section Labels**: Tracked uppercase (`letter-spacing: 1.5sp`, `font-size: 11sp`, `font-weight: Bold`, `color: DeckTextSecondary`)

---

## Component Specifications

### 1. Telemetry Ribbon (`TelemetryRibbon.kt`)
Fixed status bar displaying active protocol state:
- `● TLS 1.3 ENCRYPTED`
- `TCP 17394 [LISTEN]` vs `[STANDBY]`
- `TOFU: N PAIRED`

### 2. Station Identity Bar (`IdentityDeckBar.kt`)
Header bar showing local station monogram `[N]`, device name, short ID, and active IP address.

### 3. The Beam Visualizer (`BeamVisualizer.kt`)
Dual node boxes (`NODE: LOCAL` $\leftrightarrow$ `NODE: BROADCAST`):
- Idle state: dormant wire with subtle scan sweep.
- Active state: cyan beam streaming packet indicators proportional to throughput.

### 4. Visibility Breaker Switch (`BreakerSwitch.kt`)
Mechanical switch controlling socket listeners and beacon broadcasts (`Modifier.toggleable`, `Role.Switch`, minimum 48dp touch target).

### 5. Attach Chip Rail & Staged Filmstrip (`AttachChipRail.kt`, `StagedFilmstrip.kt`)
Horizontal selector for staging items (Files, Media, Text, Paste, Folders, Apps, Contacts) paired with a filmstrip showing item names, sizes, and remove actions.

### 6. Packet-Stream Console (`PacketStreamVisualizer.kt`, `TerminalStatsBlock.kt`)
Monospace status console showing transfer direction, payload size, speed, ETA, and 16-block chunk progress matrix (`[■■■■■■■■■■□□□□□□]`).

### 7. Consent Dialog (`ConsentDialog.kt`)
Verification dialog displaying sender details, security stamps (`[TLS 1.3 MUTUAL AUTH]`, `[TOFU: PAIRED]`), SAS digits, and payload manifest.

### 8. Settings Ledger (`SettingsScreen.kt`)
Structured settings screen with hairline dividers, security stamps, and socket parameters.

---

## Accessibility and Motion

1. **Animation Scaling**: Respects `LocalAnimationsEnabled` and system `ANIMATOR_DURATION_SCALE`. When animations are disabled, visualizers render in static states.
2. **Touch Targets**: All clickable and toggleable surfaces maintain $\ge 48\text{dp}$ touch target boundaries.
3. **Screen Reader Support**: All interactive controls declare explicit semantic roles (`Role.Button`, `Role.Switch`) and content descriptions.
