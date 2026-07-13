# Lanes — Brand & Design System

Advanced application manager for JetBrains IDEs.
Identity concept: **"Lanes"** — parallel processes running side by side, each with a live status signal. The mark is not a play button, a whale, or a helm: it is the thing the plugin actually shows you — multiple applications, alive, observed.

---

## 1. The Mark

Three rounded lanes of different lengths + two status dots.

| Element | Meaning |
|---|---|
| Three staggered lanes | Multiple applications running simultaneously (orchestration) |
| Different lengths | Independent lifecycles — processes start, run and stop on their own time |
| Status dots | Live monitoring: health, signal, observability |
| Light-blue lane/dots | Telemetry accent — the "measured" layer over the "running" layer |

The mark must always be reproduced on the 16×16 grid with lane height = 3, corner radius = 1.5 (fully rounded), vertical gap = 1.5, dot radius = 1.5.

**Never**: rotate the mark, outline it, add gradients or shadows, change lane count, or place text inside it.

---

## 2. Color Palette

### Brand
| Token | Hex | Use |
|---|---|---|
| `brand.primary` | `#3574F0` | Primary actions, lanes, links, selection |
| `brand.primary.dark` | `#548AF7` | Primary on dark theme |
| `brand.telemetry` | `#4FC3F7` | Charts, live signals, secondary lanes |
| `brand.telemetry.dark` | `#6CD1FF` | Telemetry on dark theme |
| `brand.accent` | `#7B61FF` | Memory metrics, premium highlights (use sparingly) |

### Semantic
| Token | Light | Dark | Use |
|---|---|---|---|
| `status.success` | `#4B9E52` | `#5FB865` | Running, healthy |
| `status.warning` | `#D9881E` | `#E8A33D` | Restarting, threshold approaching |
| `status.danger` | `#DB5C5C` | `#E36A6A` | Crashed, memory leak, stop actions |
| `status.paused` | `#8C8F98` | `#85888F` | Paused / not monitored |

### Metrics
| Token | Hex | Use |
|---|---|---|
| `metric.cpu` | `#4FC3F7` | CPU charts and badges |
| `metric.memory` | `#7B61FF` | Memory charts, bars and badges |
| `metric.memory.leak` | `#DB5C5C` | Leak detection overlays |
| `metric.network` | `#3574F0` | HTTP health / request charts |

### Light theme
| Token | Hex |
|---|---|
| `bg.default` | `#FFFFFF` |
| `bg.panel` | `#F7F8FA` |
| `bg.hover` | `#EEF3FD` |
| `border.default` | `#D6D9E0` |
| `border.focus` | `#3574F0` |
| `text.primary` | `#1E1F22` |
| `text.secondary` | `#6C707E` |
| `text.disabled` | `#A8ABB4` |

### Dark theme
| Token | Hex |
|---|---|
| `bg.default` | `#1E1F22` |
| `bg.panel` | `#26282C` |
| `bg.hover` | `#2E436E` (12% blue overlay) |
| `border.default` | `#3B3E45` |
| `border.focus` | `#548AF7` |
| `text.primary` | `#ECEDF0` |
| `text.secondary` | `#9DA0A8` |
| `text.disabled` | `#5A5D63` |

Gradients: avoid. If unavoidable (large marketing surfaces only), max Δ of one step in lightness of the same hue.

---

## 3. Typography

| Role | Typeface | Fallbacks |
|---|---|---|
| UI / headings | **Inter** (600/700 for headings, 400/500 for UI) | JetBrains Sans, Segoe UI, system-ui |
| Data / code / metrics | **JetBrains Mono** | SF Mono, Consolas, ui-monospace |

Rules:
- Metrics (CPU %, MB, PIDs, ports, durations) are **always** rendered in JetBrains Mono — data reads as data.
- Headings use tight letter-spacing (−0.5px at ≥24px).
- Uppercase mono labels (chips, badges) use +1.5–2.5px letter-spacing.
- No italics in UI. No thin weights (<400).

---

## 4. Iconography & UI Guidelines

### SVG grid
- Canvas: **16×16**, live area 12×12 (2px safe padding), key shapes may reach 1.5px from the edge.
- Align strokes to the 0.5 sub-grid for pixel crispness.

### Strokes
- Stroke width: **1.5px** at 16px (never thinner). Scales proportionally: 2px @ 20–24px.
- Caps and joins: **round**.
- Base color: `currentColor` (inherits theme). Accents: `#3574F0` (action/live), `#4FC3F7` (telemetry), `#DB5C5C` (destructive).
- One accent color per icon, maximum.

### Corner radius
| Element | Radius |
|---|---|
| Icon shapes (rects) | 1–2px |
| Buttons, chips | 6px (chips: full) |
| Cards, panels | 12–14px |
| Lanes (brand mark) | fully rounded (h/2) |

### Spacing
- Icon ↔ label: **6px** (16px icons), 8px (20px+).
- Between toolbar icons: **4px** (grouped), 12px between groups.
- Panel padding: **12px**; card padding: **16px**; section gap: **20px**.
- Base spacing unit: **4px** — every margin/padding is a multiple.

### Recommended icon sizes
| Size | Use |
|---|---|
| **16px** | Toolbars, gutters, tree items (default) |
| **20px** | Touch-friendly toolbars, New UI compact mode |
| **24px** | Empty-state actions, dialogs |
| **32px** | Onboarding, notifications, plugin cards |

Export each size from the same 16-grid source; do not redraw.

---

## 5. Brand Personality

Professional · Fast · Reliable · Technical · Modern · Developer-first · Minimal · Powerful.

Voice:
- Verbs first: "Restart app", "Export report" — never "Would you like to…".
- Data over adjectives: "Restarted in 1.4s", not "Blazing fast!".
- Errors state cause + next action: "web-frontend crashed (exit 137). Auto-restart in 5s — view logs."
- No emoji in product UI. No exclamation marks. Not playful — but never cold: empty states invite action.

The bar: every surface should look like it shipped from JetBrains itself.

---

## 6. Naming

Product name: **Lanes** (wordmark: `Lanes.` — the period is always rendered in `brand.primary` and represents the live status dot from the mark).
Marketplace listing: "Lanes — Run, Monitor & Automate Your Development Apps".
CLI/marketing voice uses the name as a plain noun: "Add an app to Lanes", never "the Lanes plugin experience".
