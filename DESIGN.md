# NXFR Design System — Instrument Deck (v2.0)

NXFR is a sovereign, privacy-first peer-to-peer file transfer protocol. The visual language reflects the **"Instrument Deck"**: cockpit avionics, precision data telemetry, mathematical trust, and cold cryptographic clarity.

---

## 🧭 Identity Manifesto

1. **Precision Over Playfulness**: No bubbly cards, pastel mascots, or floating cartoon shapes. Geometry is structural, angular, and functional.
2. **Trust Made Visible**: Real cryptographic invariants (TLS 1.3, TOFU validation, SHA-256 integrity streams, ephemeral session IDs) are prominently displayed in monospace text.
3. **Data in Monospace**: Device IDs, SAS codes, IP addresses, transfer throughput, and chunk manifests render strictly in tabular monospace type (`FontFamily.Monospace`).
4. **Cyan as Signal, Never Decoration**: `SignalBeam` (`#00E5FF`) is strictly reserved for active transmission states (lit beams, streaming packets, energized breaker). Inactive surfaces and chrome remain calm and neutral.
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

---

## 🔤 Typography & Font Hierarchy

- **UI Typeface**: `Inter`
- **Data / Telemetry Typeface**: `FontFamily.Monospace` (Tabular digits, uppercase telemetry flags)

---

## 🎛️ Physical Component Signatures

1. **Telemetry Ribbon**: Top status strip detailing cipher, port listener state, and TOFU trust count.
2. **The Beam Visualizer**: Two node glyphs linked by a transmission wire with packet flow.
3. **Visibility Breaker**: Industrial toggle switch powering the beam and socket listeners.
4. **Packet-Stream Console**: Transfer screen with streaming packets and mono telemetry stats.
