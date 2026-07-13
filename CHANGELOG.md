# Changelog

All notable changes to **Lanes** are documented here. Newest first.

Fork of the original [Multirun](https://github.com/rkhmelyuk/multirun) by Ruslan Khmeliuk.
Uninstall the original plugin before installing this one.

## [1.0.3] — Pick the .env profile from the toolbar
- Fixes 1.0.2: the pre-Play picker never actually rendered (the platform widget it relied on only
  shows once a non-default "execution target" is already active, which nothing ever set for a
  Lanes group or a plain Node run configuration).
- Replaced with a toolbar button next to the run configuration selector (both classic and new UI):
  shown once the selected Lanes group or native Node run configuration (Node.js, npm/pnpm/yarn,
  Karma, Jest, Mocha) has 2 or more saved `.env` profiles. Clicking it opens a small list of the
  profiles, the active one marked; picking a different one persists it as the active profile,
  exactly like switching it from the Lanes Monitor - no modal, no restart required to just change
  what the next Play/Debug will use.

## [1.0.2] — Pick the .env profile before Play/Debug (superseded by 1.0.3)
- Attempted a pre-Play environment picker in the run widget itself, before Play/Debug even starts.
  Never actually appeared in a real IDE - see 1.0.3.

## [1.0.1] — Moved to the PixelC0d3 GitHub organization
- Internal: the project moved to the [PixelC0d3](https://github.com/PixelC0d3) GitHub organization.
  The plugin id is now `io.github.pixelcodes.lanes` and the Kotlin package moved accordingly.
- No functional change; Welington Monteiro remains the plugin's maintainer/vendor.

## [1.0] — Initial release

### Grouping
- Group any number of run configurations into a single **Lanes** configuration and start them with
  one click.
- **Nesting / composite configurations**: a Lanes configuration can contain other Lanes
  configurations, so you can build a "master" configuration that starts several groups at once —
  with loop protection (detects and prevents A-contains-B-contains-A cycles).

### Execution modes
- **Parallel** or **one by one** (the next configuration starts only after the previous one has
  started — useful when "Before launch" tasks would otherwise collide).
- **Enable/disable per application** — an *On* checkbox per row; unchecked apps stay in the list
  (keeping their settings) but are not launched.
- **Ready when** (docker-compose-style `depends_on`): with one-by-one, the next application only
  starts once the previous one is actually ready — `port:3003`, `http://localhost:3003/health`
  (2xx/3xx), or `log:some text` (console output contains it). Capped at 2 minutes, then the chain
  continues anyway. Also feeds the monitor's Status column (healthy/down, re-checked every refresh).
- **Delay between configurations** (one-by-one only): `0` starts immediately, a positive value
  waits up to N seconds (with a countdown), a negative value waits for the previous process to
  *complete* first (serial execution). Locale-aware (accepts `,` or `.` as the decimal separator).

### Environment variables & .env files
- Define environment variables directly on the Lanes configuration — applied to every child
  configuration, overriding same-named variables, and propagating through nested Lanes
  configurations too.
- **Environment file with profiles**: point the *Environment file* field at one or more `.env`
  files (multi-select browse) and switch between them from a dropdown — no retyping paths.
  Re-read on every run; the configuration's own Environment variables field still wins on
  conflicts.
- **Per-application env file override**: an *Env file (app)* column lets a single app use its own
  `.env` file instead of the group's.
- **Native Play/Debug support**: the same `.env`-profile loading is available directly on
  Node-based run configurations (Node.js, npm/pnpm/yarn, Karma, Jest, Mocha) — not just inside a
  Lanes group. A switch button on the run toolbar restarts that same run with the chosen profile.
  Optional module: only active in IDEs with the JavaScript plugin; uninstalling the plugin removes
  it cleanly.
- Works with any configuration type that exposes environment variables (Node.js, npm, Java
  Application, …); other types run unchanged.

### Execution presets
- Save the current On/Off selection of applications plus the active environment profile as a named
  **preset**, and switch between scenarios ("backend only", "full stack", …) from a dropdown — no
  more re-checking boxes by hand.

### Import from docker-compose.yml
- Reads a compose file and applies it to run configurations already in the list whose name matches
  a service: `mem_limit` (and `deploy.resources.limits.memory`) → Memory limit, `env_file` → an
  environment profile, `depends_on` → reorders the list and adds a `port:<published>` Ready when
  gate. Services without a matching configuration are reported and skipped — it never creates one.

### Per-application memory limit
- An editable **Memory limit (MB)** column applies a heap cap at launch
  (`NODE_OPTIONS --max-old-space-size` for Node.js, `JAVA_TOOL_OPTIONS -Xmx` for the JVM) — the
  process-level analog of Docker's `mem_limit`.

### Lanes Monitor (docker-stats-style process table)
- Lists **every** process the IDE is running — apps started by Lanes *and* standalone (singleton)
  runs — with PID, ports, uptime, status, memory usage/limit, memory %, memory trend and CPU %.
  Each application is measured as its whole process tree (e.g. an `npm` wrapper plus its `node`
  child), refreshed automatically every 2 seconds. A distinct "configured" icon marks every saved
  Lanes instance everywhere the platform shows it (switcher, toolbar widget, Edit Configurations
  tree, monitor).
- **Env** column shows the active profile — click to view the variables actually loaded (with a
  name filter and a mask-values toggle for screen sharing) — for grouped apps and standalone runs
  alike.
- **Ports** are clickable (opens `http://localhost:<port>`); **Kill Process on Port…** stops
  whatever is listening on a port, even processes the plugin didn't start.
- **Mem trend** sparkline (color-coded by proximity to the limit) opens a full-session memory chart
  with labeled axes, CSV export, and a **leak-analysis** tab (growth rate, projected growth/hour,
  R², a per-process breakdown, and a text export).
- **Restart / Stop / Force Kill** per row or in batch (multi-select survives the automatic
  refresh); **Restart All** and **Restart Unhealthy** toolbar actions; **Switch Environment**
  per-row (just that app) or group-wide (toolbar, applies to every running app at once, preserving
  each app's executor — Debug comes back as Debug).
- **Show/Hide columns** picker; **Aggregated Logs** tab merges every running app's console into one
  color-coded stream, with an app selector, composable filters (substring/regex, show/hide,
  case-sensitive, minimum log level) and an ANSI toggle for real terminal colors.
- Shows a branded empty-state illustration when nothing is running instead of a plain text line.
- Windows support via PowerShell `Get-Process` for memory/CPU (Ports and Kill-by-Port need `lsof`,
  Linux/macOS only).

### Status bar widget
- A compact indicator shows how many applications Lanes is running, their combined memory and how
  many are unhealthy — e.g. `▶ 3 apps · 1.2 GiB · ⚠ 1`. Click it to open the Lanes Monitor; it
  hides itself when nothing is running.

### Restart policies & alerts (docker style)
- **Restart application on crash** — relaunches an app that exits with a crash code (max 3 attempts
  per run); intentional stops never trigger it.
- **Crash notification with Restart** — when crash-restart is off (or exhausted), a one-click
  Restart button appears instead of failing silently.
- **Unhealthy alert** — an app whose `port:`/`http` Ready when condition stays down for 3
  consecutive checks is reported unhealthy with a Restart button; re-arms on recovery.
- **Sustained CPU alert** — a configurable CPU % threshold held for 3 consecutive checks raises a
  notification with Restart (0 disables it; values above 100% make sense on multi-core machines).
- **Memory limit alert** — configurable threshold (default 90%) with a **Notify** or **Restart
  application** action, docker-like OOM handling.

### Console tab handling
- Mark the tab of a failed configuration; optionally close tabs of successfully completed ones;
  reuse tabs for succeeded and/or failed runs; running configurations show a `*` in their tab
  title.
- **Save console logs to folder** — writes each configuration's output as `<name>.log` via the
  IDE's standard mechanism, relative paths resolved against the project root.

### Restarting and stopping
- **Restart on rerun** (default on) — running a Lanes configuration that's already running stops
  its own processes first, waits for termination, then starts again; other running Lanes groups are
  untouched. Can be disabled per configuration.
- **Stop Lanes** action stops everything the plugin started (and cancels anything still queued),
  available from `Run → Stop Lanes` and via <kbd>Shift</kbd>+<kbd>Alt</kbd>+<kbd>K</kbd> (macOS:
  <kbd>Control</kbd>+<kbd>Alt</kbd>+<kbd>K</kbd>).

### Supported executors
Run, Debug, Run with Coverage, Profiler, and JRebel (Run and Debug).
