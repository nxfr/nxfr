# NXFR Design System — Instrument Deck (v2.0)

NXFR is a sovereign, privacy-first peer-to-peer file transfer protocol. The visual language reflects the **"Instrument Deck"**: cockpit avionics, precision data telemetry, mathematical trust, and cold cryptographic clarity.

---

## 🧭 Identity Manifesto

1. **Precision Over Playfulness**: No bubbly cards, pastel mascots, or floating cartoon shapes. Geometry is structural, angular, and functional.
2. **Trust Made Visible**: Real cryptographic invariants (TLS 1.3, TOFU validation, SHA-256 integrity streams, ephemeral session IDs) are prominently displayed in monospace text.
3. **Data in Monospace**: Device IDs, SAS codes, IP addresses, transfer throughput, and chunk manifests render strictly in tabular monospace type (`FontFamily.Monospace`).
4. **Cyan as Signal, Never Decoration**: `SignalBeam` (`#00E5FF`) is strictly reserved for active transmission states (lit beams, streaming packets, energized breaker). Inactive surfaces and chrome remain calm slate.
5. **The Core Motif — THE BEAM**: Two node glyphs connected by a directional transmission channel. Idle state features a slow laser sweep; active state streams real-time data packets.

---

## 🎨 Color Palette Tokens (Instrument Deck)

### Dark Mode (Cockpit Obsidian)
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

### Signal Tokens (Functional Status)
| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `SignalBeam` | `#00E5FF` | Active transmission cyan |
| `SignalBeamGlow` | `#3300E5FF` | 20% alpha outer beam glow |
| `SignalStandby` | `#475569` | Dormant wire slate |
| `SignalAlert` | `#FF3366` | Breaker trip / Error coral |
| `SignalSuccess` | `#00E676` | Cryptographic verification green |
| `SignalWarning` | `#FFFFB300`| Action required amber |

### Light Mode (Drafting Paper)
| Token Name | Hex Code | Purpose |
| :--- | :--- | :--- |
| `DeckPaper` | `#F8FAFC` | Root background |
| `DeckPaperSurface` | `#FFFFFF` | Structural panel |
| `DeckPaperGridLine` | `#CBD5E1` | Grid border |
| `DeckPaperTextPrimary`| `#0F172A` | Primary readout |

### OLED Black Mode
- Surfaces clamp to pure `#000000` with 1dp `DeckGridLineBright` outlines for maximum contrast and battery preservation on AMOLED panels.

---

## 🔤 Typography & Font Hierarchy

- **UI Typeface**: `Inter` (Display, Headlines, Titles, Labels)
- **Data / Telemetry Typeface**: `FontFamily.Monospace` (Tabular digits, node IDs, SAS auth codes, socket parameters, transfer statistics)
- **Section Headers**: Tracked out uppercase (`letter-spacing: 1.5sp`, `font-size: 11sp`, `font-weight: Bold`, `color: DeckTextSecondary`).

---

## 🎛️ Physical Component Signatures

### 1. Telemetry Ribbon (`TelemetryRibbon.kt`)
- Fixed top status strip displaying protocol invariants:
  - `● TLS 1.3 ENCRYPTED`
  - `TCP 17394 [LISTEN]` vs `[STANDBY]`
  - `TOFU: N PAIRED` (real-time query)

### 2. Station Identity Bar (`IdentityDeckBar.kt`)
- Angular monogram badge `[N]`, station call-sign, `#shortId`, active IP tag in monospace, and Station Telemetry details sheet.

### 3. The Beam Visualizer (`BeamVisualizer.kt`)
- Dual node boxes (`NODE: LOCAL` $\leftrightarrow$ `NODE: BROADCAST`).
- Idle state: dormant wire with 2.4s laser scan sweep.
- Active state: energized laser cyan beam streaming real-time data packets.

### 4. Visibility Breaker Switch (`BreakerSwitch.kt`)
- Industrial mechanical switch using `Modifier.toggleable` (role = `Role.Switch`, $\ge 48\text{dp}$ touch target, direct haptics). Controls native socket listeners and persists `visible_enabled`.

### 5. Attach Chip Rail & Staged Filmstrip (`AttachChipRail.kt`, `StagedFilmstrip.kt`)
- Horizontal rail of 7 angular chips (`[+ FILE]`, `[📷 MEDIA]`, `[📝 TEXT]`, `[📋 PASTE]`, `[📁 FOLDER]`, `[📦 APP]`, `[👤 CONTACT]`).
- Horizontal filmstrip with angular `[×]` remove buttons, monospace byte pills, and top status banner (`STAGED: N ITEMS · X.X MB`).

### 6. Packet-Stream Console (`PacketStreamVisualizer.kt`, `TerminalStatsBlock.kt`)
- Active Beam transmission with throughput-scaled packet velocity and a 16-block chunk matrix (`CHUNKS: [■■■■■■■■■■□□□□□□] 62%`).
- Recessed console well with strict monospace readouts for stream direction, payload bytes, current/peak speed, ETA, socket, and SHA-256 integrity streaming.

### 7. Cryptographic Consent Manifest (`ConsentDialog.kt`)
- Verification sheet with security stamp seals (`[TLS 1.3 MUTUAL AUTH]`, `[TOFU: PAIRED]`), sender telemetry, large SAS auth digits (`● 123 456 ●`), and tabular payload ledgers.

### 8. Settings Ledger (`SettingsScreen.kt`)
- High-security archival ledger with 0.5dp hairline dividers, security stamp seals (`[SEALED: TLS 1.3]`, `[TRUSTED: N]`, `[VERIFIED]`), and monospace socket telemetry parameters.

---

## ♿ Accessibility & Motion Contract

1. **System Animation Scaling**: Respects `LocalAnimationsEnabled` and Android's `ANIMATOR_DURATION_SCALE`. When animations are OFF, sweeps and pulses snap instantly to static states.
2. **Touch Targets**: All clickable and toggleable surfaces maintain $\ge 48\text{dp}$ touch target boundaries.
3. **Screen Reader Semantics**: All interactive controls declare explicit `Role.Button`, `Role.Switch`, or `contentDescription` attributes.
