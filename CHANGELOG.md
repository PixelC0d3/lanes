# Changelog

All notable changes to **Multiple Run** are documented here. Newest first.

Fork of the original [Multirun](https://github.com/rkhmelyuk/multirun) by Ruslan Khmeliuk.
Uninstall the original plugin before installing this one (existing configurations keep working).

## [2.0.1] — Verifier: migrate off ProgramRunner.execute(env, callback)
- **Deprecated API removed:** the launch pipeline no longer calls the deprecated
  `ProgramRunner.execute(environment, callback)`. The environment is now built **with its callback
  attached** through `ExecutionEnvironmentBuilder.build(callback)` and run with the non-deprecated
  `execute(environment)`. A holder (`AtomicReference`) breaks the env↔callback cycle (the crash-restart
  action needs the environment). No functional change to launching.
- Effect (Marketplace verifier): **2023.3–2024.x now verify with 0 deprecated warnings** (this was
  the only one there); 2025.x/2026.x drop to the `FileSaverDescriptor` (and, on 2026.2, the
  `EnvironmentVariablesComponent`) warnings, which can't be removed while the 2023.3 baseline lacks
  their non-deprecated replacements.

## [2.0.0] — Kotlin migration begins: toolchain + first module
- **Build now compiles Java and Kotlin together** (Kotlin `2.2.20`, JVM target 17). The Kotlin
  stdlib is **provided by the IDE, not bundled** into the plugin zip
  (`kotlin.stdlib.default.dependency=false`), so there is no stdlib clash and the artifact stays a
  single jar.
- **First class migrated:** `LogFilter` (the aggregated-Logs filter — pure logic, fully unit-tested)
  is now Kotlin. Its public API is unchanged (`@JvmStatic` factories), so the existing Java tests and
  callers keep working untouched. 145 tests still green.
- The migration is **incremental**: complex/critical classes (the `.form`-bound run-config editor,
  the monitor panel, the launch pipeline) stay Java for now and move to Kotlin gradually; Java and
  Kotlin interoperate in the same module in the meantime.

## [1.45.3] — Marketplace verifier: internal API removed
- **Internal API removed (the approval blocker):** `MultirunRunner` now extends
  **`GenericProgramRunner`** instead of calling the internal `ExecutionManager.startRunProfile` — the
  platform performs that call itself, so the plugin no longer touches any internal API. No functional
  change to launching.
- **Deprecated APIs migrated (3):** `new URL(String)` → `URI.create(url).toURL()`;
  `DefaultActionGroup.addAll(ActionGroup)` → the row actions are added individually;
  `FileChooserDescriptorFactory.createSingleFileDescriptor()` → `createSingleFileNoJarsDescriptor()`.
- The verifier now reports **0 internal / 3 deprecated** (was 1 internal / 6–7 deprecated). The
  remaining warnings — `ProgramRunner.execute(env, callback)` (in the launch pipeline) and
  `FileSaverDescriptor(…, extensions)` (its non-varargs constructor does not exist on the 2023.3
  baseline we compile against) — are non-blocking and will be handled when those classes are migrated
  to Kotlin.

## [1.45.2] — Env column shows the effective per-app environment
- **Fix:** after switching an app's environment (per-app dropdown), the monitor's **Env column now
  shows the env actually in effect** for that app — its per-app override — instead of still showing
  the group's profile. The registry now records the effective env file (per-app override when set,
  else the group profile).
- **Switch Environment modal:** marks and **pre-selects the active** environment, so it opens on the
  one already in use.
- **Group-wide switch is authoritative:** the toolbar *Switch Environment* button now **clears the
  running apps' per-app overrides** before applying the chosen profile, so every app follows it and
  the Env column shows it uniformly (a per-row switch still sets only that app's override).

## [1.45.1] — Env switching: per-app dropdown + preserved executor
Refines the 1.45.0 env switching after testing:
- **Per-app Env dropdown:** the monitor's *Env* cell dropdown now switches **just that application**
  (a per-app override) and restarts only it — the rest of the group keeps running with its own
  environment. The toolbar **Switch Environment** button stays the group-wide "switch all" option.
- **Executor preserved:** both keep each app's executor — an app running under **Debug comes back
  under Debug** (Run stays Run, …). Because Multiple Run bakes the environment into each app at
  launch, a plain restart would keep the old values, so switching stops the app and **relaunches it
  through the group** with the new profile, so it stays tracked in the monitor and shows the new
  environment.
- **Icon:** the *Switch Environment* toolbar button now uses an environment-like icon (was a reddish one).

## [1.45.0] — Environment switching from the monitor
- **Multi-file env selection:** the group's *Environment file* browse button now accepts **several
  `.env` files at once** (Ctrl/Shift-select) when building the profile dropdown — no more one click
  per file.
- **Env column dropdown:** in the monitor, a group with more than one env profile shows the **Env
  cell as a dropdown** (`▾`) to switch the environment; a *view loaded variables* entry still opens
  the read-only viewer.
- **Batch Switch Environment:** a new toolbar button (badge = number of running apps) opens a modal
  to switch the environment of the running apps from a single place, instead of app by app.
- **Tests:** +6 unit tests (multi-file selection merge, switchable-profile union, batch grouping).
  145 total.

## [1.44.0] — Monitor: multi-selection survives refresh + Restart All
- **Multi-selection batch actions:** selecting several applications and then running *Restart*,
  *Stop* or *Force Kill* now keeps the **whole selection across the automatic 2s refresh**. Before,
  the refresh collapsed the selection to a single row, so a batch action could end up acting on just
  one app — now it always targets every app you selected.
- **Restart All:** a new toolbar button (next to *Stop All*) with a **badge showing the number of
  running applications**; one click relaunches every running app instead of restarting them one by one.
- **Tests:** +3 unit tests for the multi-selection restore. 139 total.

## [1.43.0] — IntelliJ Platform Gradle Plugin 2.x + local Plugin Verifier
- **Build:** migrated from the legacy `org.jetbrains.intellij` 1.16 Gradle plugin to the
  **IntelliJ Platform Gradle Plugin 2.x** — the tooling JetBrains now recommends. Same targets
  (IC 2023.3, `since-build 233`, no upper bound), same GUI-form instrumentation (verified in the
  produced jar), no functional change to the plugin itself.
- **Plugin Verifier locally:** `./gradlew verifyPlugin` now runs the JetBrains Plugin Verifier
  against the **same IDE matrix the Marketplace uses** (2023.3.8 → 2026.1.4), so API problems are
  caught before publishing. Run a subset with `-PverifierIdes=2023.3.8,2026.1.4`.
- **Tests:** +6 unit tests covering executor matching (`Run`/`Debug`/`Coverage`/JRebel,
  case-insensitive) and the memory/CPU alert threshold clamps. 136 total.

## [1.42.2]
- Maintenance (Marketplace verifier): migrated off two flagged platform APIs, with no functional
  change:
  - the **docker-compose import** button no longer uses `ToolbarDecorator.addExtraAction(AnActionButton)`
    (scheduled for removal) — it uses the `addExtraAction(AnAction)` overload with a `DumbAwareAction`;
  - child processes are launched via the public `ProgramRunner.execute(environment, callback)` instead
    of the internal `ExecutionEnvironment.setCallback`.
  - Note: `ExecutionManager.startRunProfile` (internal) is kept for now — it is the core launch
    mechanism inherited from the original Multirun and has no clean public replacement.

## [1.42.1]
- Fix: **Logs tab layout** — the filter controls now sit as a full-width strip on **top** and the
  log fills the whole width **below** them (they were being laid out as a left-hand column, which
  pushed the log to the right and left a large empty area).

## [1.42.0] — Clean/colored ANSI logs + rolling zip archive
- Logs tab: console output is readable when apps emit **ANSI color codes** (webpack, nest, etc.).
  By default the escape codes are **stripped** for a clean log; tick **ANSI** in the header to
  **render the real terminal colors** instead. Filtering and level detection always run on the
  clean text, so results are the same either way.
- **Build:** `buildPlugin` keeps a rolling archive of the **last 5 built plugin zips** in `dist/`
  (which lives outside `build/` and so survives `gradlew clean`), so recent artifacts are never
  lost. No functional change to the plugin.

## [1.41.0] — Log filters per app + accurate Stop count
- Logs tab: an **App** selector narrows the aggregated stream to a single application (or all of
  them), and **advanced, composable filters** arrive: several **comma-separated terms** combined
  with *any*/*all*, matched as **substring or regex**, **case-sensitive** or not, to **show** or
  **hide** matches, plus a minimum **log level** (Info+ / Warn+ / Errors) detected from the text.
  In regex mode the whole field is one pattern (so a quantifier like `\d{3,}` works).
- Monitor: the **Stop Multiple Run** count now includes apps **restarted individually from the
  monitor** (which the IDE relaunches as standalone runs). It no longer under-counts, and the
  button stops those too, so the number and the action stay consistent.

## [1.40.0] — Leak analysis that works, process count, quick Analysis
- The memory **Analysis** tab now loads its per-process breakdown correctly — it was stuck on
  "Sampling…" because a modal-dialog `invokeLater` needed `ModalityState.any()`.
- **Richer leak analysis**: a steady-leak verdict backed by the growth rate (MiB/min), projected
  growth per hour, the **R²** of the trend (how linear/steady the growth is) and how often memory
  was **never freed** (monotonic fraction).
- The Analysis tab gained a **process filter** (by PID or command) and an **Export analysis…**
  button that writes the verdict plus the per-process breakdown to a text report.
- New toolbar button opens the memory chart **straight on the Analysis tab** for the selected
  application — no need to click the Mem trend sparkline first.
- The **Stop Multiple Run** toolbar button now shows the **number of running processes** next to a
  stop icon (like WebStorm), instead of a bare icon easy to confuse with the per-row Stop.

## [1.39.0] — Memory chart axes, leak analysis, column chooser
- The memory chart (click a **Mem trend** sparkline) now has **labeled axes** — X is elapsed time,
  Y is memory (RSS) — with grid lines and tick labels, plus the peak annotation.
- The chart pop-up gained an **Analysis** tab: a memory-trend / possible **leak verdict**
  (growing / stable / shrinking, with the growth rate in MiB/min and first→last / min→peak figures),
  and a **per-process breakdown** of the application's process tree (PID, command, memory, % of
  tree) so a runaway child process is easy to spot.
- Monitor: **show/hide columns** — a toolbar button opens a checkbox list to pick which columns are
  visible (the Name column is always shown).
- Monitor: the **Stop Multiple Run** toolbar button now has a visible icon (it was blank because of
  a stale icon path).

## [1.38.0] — Environment viewer + Running status
- Click a running application's **Env** cell in the monitor to open a viewer with the environment
  variables Multiple Run loaded for it at launch (group variables + group env file + memory-limit
  options + per-app env file, merged). A **filter by variable name** narrows the list and a **Mask
  values** toggle hides values for screen sharing. Values are never logged — only shown on demand.
- The **Status** column now shows **running** (green) for every live application, refining to
  **healthy**/**down** when a port/http *Ready when* condition is set — it no longer stays blank.

## [1.37.1]
- **Build:** disabled `buildSearchableOptions` — the plugin has no Settings pages to index, and that
  step launched a headless IDE that intermittently failed with a platform
  `ConcurrentModificationException`. Builds are now deterministic and faster. No functional change.

## [1.37.0] — Sustained CPU alert
- Set a **CPU alert %** on the group. An application whose CPU stays at or above it for **3
  consecutive** background checks raises a notification with a one-click **Restart** button; the
  alert re-arms when it drops back under the threshold.
- `0` disables it. Values above 100% make sense on multi-core machines (docker-stats-style CPU,
  summed across cores). Cross-platform (alert only — no hard cgroup/taskset limiting).

## [1.36.0] — Batch actions in the monitor
- Select several rows (Ctrl/Shift-click) and **Restart**, **Stop** or **Force Kill** act on all of
  them at once (Force Kill lists them for confirmation first).
- New **Restart Unhealthy** action restarts every application whose `port:`/`http` readiness check
  is currently down.

## [1.35.0] — Memory chart + CSV export
- Click a row's **Mem trend** sparkline to open a full-session memory chart for that application
  (the sparkline only shows the last minute).
- The chart pop-up can **export the history to CSV** (timestamp, RSS, percent).

## [1.34.0] — Crash & health alerts with Restart
- When an app crashes and **Restart on crash** is off (or its attempts are used up), a notification
  now offers a **Restart** button instead of failing silently.
- An application whose `port:`/`http` **Ready when** condition stays down for **3 consecutive**
  background checks is reported **unhealthy**, also with a **Restart** button. The alert re-arms when
  the app recovers.

## [1.33.0] — Status bar widget
- A compact indicator shows how many applications Multiple Run is running, their combined memory and
  how many are unhealthy — e.g. `▶ 3 apps · 1.2 GiB · ⚠ 1`.
- **Click it** to open the Multiple Run Monitor. It hides itself when idle; toggle it from the status
  bar widgets menu.

## [1.32.0] — Per-application environment file
- The applications table has an **Env file (app)** column. Point an app at its own `.env` file and it
  **overrides the group's environment** (variables table and group env file) for that app only.
- Empty = use the group's environment. Re-read on every run; relative paths resolve against the
  project root.

## [1.31.0] — Clickable ports
- Click a port in the monitor's **Ports** column to open `http://localhost:<port>` in the browser
  (a menu lets you pick when a process listens on several ports). Ports render as links.

## [1.30.0] — Import from docker-compose.yml
- The editor's list toolbar gains an import button that reads a compose file and applies it to the
  run configurations whose **name matches a service**:
  - `mem_limit` (and `deploy.resources.limits.memory`) → **Memory limit**
  - `env_file` → an **environment profile**
  - `depends_on` → the list is **reordered** (dependencies first) and a `port:<published>`
    **Ready when** gate is added
- It never creates run configurations: services without a matching one are reported and skipped.

## [1.29.0] — Execution presets
- Save the current **On/Off** selection of applications plus the active **environment profile** as a
  named preset, and switch between scenarios ("backend only", "full stack", …) from a dropdown in the
  editor — no more re-checking boxes by hand.

## [1.28.1] — Fix: editor could fail to open
- Fix: the run configuration editor (Edit Configurations for a Multiple Run entry) could fail to open
  after the 1.27.2 package rename — the GUI form bound to the editor was accidentally removed.
  Restored it. **Recommended update if you installed 1.27.2 or 1.28.0.**

## [1.28.0] — Aggregated Logs tab
- The Multiple Run Monitor gains a second **Logs** tab: the console output of every running
  application merged into one stream — like `docker compose logs -f`. Each line is prefixed with its
  application name in a distinct color, and a filter field narrows the view to matching lines.
- The process table is now the **Processes** tab.

## [1.27.2] — Internal: package rename
- Renamed the Java package to `io.github.welingtonmonteiro.multiplerun`. No user-facing change —
  existing run configurations keep working (the `Multirun` configuration type id is unchanged).
- ⚠️ This version had a regression that could stop the run-configuration editor from opening. Install
  **1.28.1** or later.

## [1.27.1]
- Fix: the "Memory limit (MB)" and "Ready when" cells no longer lose focus while typing (the cell
  editor was being closed by the dialog's validation cycle).

## [1.27]
- Monitor: now lists **every** process the IDE is running — standalone (singleton) runs included, not
  only the ones started by Multiple Run. The Name column shows the origin: the Multiple Run icon for
  grouped apps, the run configuration's own icon (node, npm, jest, …) for standalone ones.
- Monitor: an application restarted from the monitor no longer disappears from the list, and keeps
  showing its group, env profile and memory limit.
- Monitor: columns are resizable — drag the header edges to adjust widths.

## [1.26]
- Monitor: new **Mem trend** sparkline column — a mini chart with the memory history of the last
  minute per application (green/orange/red by how close to the limit it is), so leaks and growth
  trends are visible at a glance.
- Windows support for the monitor: memory and CPU are sampled through PowerShell `Get-Process` when
  `ps` is not available. (Ports and kill-by-port still need `lsof`, i.e. Linux/macOS.)

## [1.25] — Restart policies (docker style)
- "Restart application on crash" relaunches an app that exits with a crash code (max 3 attempts per
  run); stops via the stop buttons never trigger it.
- The memory limit alert threshold is now configurable (default 90%) and the action can be **Notify**
  or **Restart application** — docker-like OOM handling.

## [1.24]
- New: enable/disable checkbox per application — unchecked apps stay in the list but are not launched.
  Great for temporarily skipping a service without losing its settings.
- New: **Ready when** column, a docker-compose-like readiness gate for one-by-one starts. The next
  application only starts when the previous one is ready: `port:3003` (TCP port open), an http(s)
  health URL (2xx/3xx) or `log:some text` (console output contains it). Capped at 2 minutes, then the
  chain continues anyway.
- Monitor: new **Status** column — apps with a port/http readiness condition are re-checked on every
  refresh and show healthy/down, like `docker ps`.
- Monitor: double click on a row jumps to the console tab of that application.

## [1.23] — Environment profiles
- The Environment file field is now an editable dropdown that remembers every `.env` file you use —
  switch between environments (for example `.env.development`, `.env.staging`, `.env.production`) by
  picking a profile, no more retyping paths. The monitor shows the active profile of each running
  application in the new **Env** column.

## [1.22.1]
- Monitor: the selected row is no longer deselected by the automatic refresh.
- Monitor: fix CPU % stuck at 0.00% on Linux — the `ps` TIME column only has 1-second resolution, so
  the sampler now reads `/proc/[pid]/stat` (10 ms ticks) instead.

## [1.22]
- Monitor: **Restart** action per row — stop and start again only the selected application, the rest
  of the group keeps running.
- Monitor: CPU % is now instantaneous (delta between two samples, like docker stats) instead of the
  average since the process started.
- New: memory limit alert. When an application with a configured memory limit crosses 90% of it, the
  IDE shows a warning notification — even with the monitor closed. One alert per process.
- Monitor: new **Uptime** column.

## [1.21]
- Monitor: new **Ports** column showing the TCP ports each application is listening on (like the
  PORTS column of `docker ps`).
- Monitor: **Stop** and **Force Kill** actions per row (toolbar and right-click) — Force Kill sends
  SIGKILL to the whole process tree of the selected application.
- Monitor: **Kill Process on Port** action — type a TCP port and kill whatever process is listening
  on it (with confirmation), even processes not started by the plugin. Goodbye `EADDRINUSE`.

## [1.20] — Multiple Run Monitor
- New: **Multiple Run Monitor** tool window (bottom stripe, also under `Run → Multiple Run Monitor`).
  A docker-stats-like live table with every application started by Multiple Run: PID, memory usage vs.
  the configured limit (or the machine total), memory percentage and CPU. Measures the whole process
  tree of each application (npm wrapper + node child, etc.) and refreshes automatically every 2
  seconds.

## [1.19] — Per-application memory limit
- New: the configurations list is now a table with an editable **Memory limit (MB)** column — type a
  heap cap right next to each application and it is applied at launch as
  `NODE_OPTIONS --max-old-space-size` (Node.js) and `JAVA_TOOL_OPTIONS -Xmx` (JVM), the process-level
  analog of docker's `mem_limit`.

## [1.18] — Save console logs
- New: point the configuration to a folder and the console output of every configuration in the list
  is also written there as `<name>.log`, using the IDE's standard "save console output to file"
  mechanism. Relative paths resolve against the project root.

## [1.17] — Environment file
- New: point the Multiple Run configuration to a `.env` file (browse button or type the path; relative
  paths resolve against the project root) and its `KEY=VALUE` entries are applied to every
  configuration in the list. The file is re-read on every run; variables configured manually in the
  Environment variables field win over the file on conflicts.
- Environment override now also reaches npm/pnpm/yarn run configurations.
- Diagnostics: `idea.log` records which injection strategy each grouped configuration took (variable
  names only, never values).

## [1.16.2]
- Migrate off deprecated platform APIs flagged by the Marketplace verifier (`RunnerRegistry.getRunner`,
  `ProcessAdapter`, `ListCellRendererWrapper`), keeping the plugin compatible with future IDE
  versions. No functional changes.

## [1.16.1]
- Use an own id for the "Stop Multiple Run" action, fixing the "ID is already taken"
  `PluginException` on startup when the original Multirun plugin is installed alongside. Note: the two
  plugins still cannot run together (they share the run configuration type) — uninstall the original
  Multirun.

## [1.16] — Restart behavior
- New: like the built-in Compound configuration, running a Multirun that is already running now stops
  the processes it started before (waiting for their termination) and then starts again — no need to
  use "Stop Multirun" first. Enabled by default; can be disabled per configuration with the new
  "Restart running configurations before starting" option. Only the processes of the restarted
  Multirun are stopped.
- The run configuration type is now shown as **Multiple Run** in the Add New Configuration list (the
  internal type id is unchanged, existing configurations keep working). The stop action is now "Stop
  Multiple Run".

## [1.15.2]
- Reference grouped run configurations by their configuration type id instead of the display name.
  Fixes matching with plugins whose type display name differs (e.g. BashSupport Pro's Shell-compatible
  configurations) and with translated IDEs (language packs). Existing configurations keep working and
  are migrated automatically on the next save.

## [1.15.1]
- Fix delay value corruption in locales that use a comma as the decimal separator (German, pt-BR, …):
  the field now accepts both "." and "," and persisted values are no longer misread as tens (e.g.
  "-1.0" turning into -10).

## [1.15] — Environment variables
- New: Environment variables option on the Multirun configuration. The variables are applied to every
  configuration in the list (overriding same-named variables), including nested Multirun
  configurations. The "Include system environment variables" flag is applied to children as well.
