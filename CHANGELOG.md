# Changelog

All notable changes to **Multiple Run** are documented here. Newest first.

Fork of the original [Multirun](https://github.com/rkhmelyuk/multirun) by Ruslan Khmeliuk.
Uninstall the original plugin before installing this one.

## [2.1.4] — Fix "configured" icon never showing on the tree or the toolbar widget
- **Fix:** the 2.1.3 "configured" icon never showed up in the "Edit Configurations" tree's leaf
  nodes or the toolbar Play/Debug widget, for any configuration, old or new - only the Multiple Run
  Monitor was correct (it reads `MultiplerunIcons.Configured` directly in our own code).
- **Real root cause** (found by disassembling the real installed WebStorm build, and by checking how
  the original upstream [Multirun](https://github.com/rkhmelyuk/multirun) structured its
  `ConfigurationType`): `MultiplerunConfigurationType` extended `SimpleConfigurationType`, which acts
  as both the type *and* its own factory. `SimpleConfigurationType.getIcon(RunConfiguration)` is
  **`final`** and unconditionally returns the type-level icon, discarding the configuration argument.
  Both the tree's non-edited leaf nodes and the toolbar widget resolve their icon through
  `ProgramRunnerUtil.getConfigurationIcon() -> getRawIcon() ->
  settings.getFactory().getIcon(settings.getConfiguration())` - with a `SimpleConfigurationType`,
  that call could never reach `MultiplerunRunConfiguration.getIcon()` no matter what it returned. The
  first attempt at this fix (clearing the platform's icon cache on project open) didn't help because
  the cache was recomputing correctly the whole time - just always from the same wrong, unreachable
  place.
- Fixed by switching to a plain `ConfigurationFactory` (added to a `ConfigurationTypeBase`, the same
  shape the original Java plugin always used) with its own `getIcon(RunConfiguration)` override that
  delegates back to `configuration.getIcon()`. Existing saved configurations are unaffected: writing
  a `factoryName` attribute was already skipped entirely for `SimpleConfigurationType`-based types, so
  none of them have one persisted, and the platform treats a missing `factoryName` as an automatic
  match for a type's only factory regardless of its id.
- Kept the `postStartupActivity` (`MultiplerunConfiguredIconRefreshActivity`) that clears the cached
  icon entry for every saved Multiple Run configuration on project open, so the fixed icon appears
  immediately rather than waiting for the platform's own periodic cache revalidation.
- 152 tests pass. This part (the platform's configuration-type/factory wiring and its in-memory icon
  cache) has no headless test coverage, same gap as icon loading in general - verify visually: reopen
  "Edit Configurations" and confirm every saved Multiple Run instance (not just newly added ones)
  shows the configured icon, and select one on the toolbar Play/Debug widget to confirm it matches
  there too.

## [2.1.3] — "Configured" icon for every Multiple Run instance
- **Feature:** a Multiple Run configuration now shows a distinct **"configured"** icon (lanes wrapped
  by a restart/orchestration arrow) instead of the plain Lanes mark, everywhere the platform renders
  that specific saved instance: the run/debug switcher dropdown, the toolbar Play/Debug widget, and
  the leaf nodes of the "Edit Configurations" tree. The type-level icon is untouched: the "Multiple
  Run" category node in that same tree and the entry in "Add New Configuration" still show the plain
  Lanes mark, since those represent the type in general, not one specific configuration.
- Implemented via `RunConfiguration.getIcon()` (the SDK's per-instance icon hook - `RunManagerImpl`'s
  icon cache calls `settings.getConfiguration().getIcon()` directly, confirmed by disassembling the
  platform classes), not a UI-surface-by-surface patch, so it stays correct anywhere the platform
  decides to render a saved configuration's icon.
- Deliberately unconditional, not "only once it has child apps" as first implemented: that icon
  cache stores whatever `getIcon()` returns on the FIRST call for a configuration and never
  recomputes it. On a real IDE that first call can land before `readExternal` finishes populating
  the configuration's fields from its saved XML, permanently locking in the "empty" icon for a
  config that in fact already has apps - confirmed by disassembling `RunConfigurationIconAndInvalidCache`
  on a real installed build, not just the compile-time SDK. Any condition here would need data
  available synchronously at construction, which "has child apps" isn't (it's deserialized state).
- The Multiple Run Monitor's Name column now shows the same "configured" icon for every grouped app
  row.
- 152 tests pass.

## [2.1.2] — Fix ClassCastException on the monitor's running-apps badge
- **Fix (crash):** starting any run configuration threw `ClassCastException: CountBadgeIcon cannot
  be cast to class com.intellij.openapi.util.ScalableIcon` from
  `SquareStripeButton.updatePresentation`. The New UI's tool window stripe button hard-casts
  whatever icon `ToolWindow.setIcon(...)` was given to `ScalableIcon` - the 2.1.1 badge icon was a
  hand-rolled `Icon` that didn't implement it. Rebuilt the badge with `LayeredIcon` (base icon +
  a small count-bubble layer) instead: `LayeredIcon` extends `JBCachingScalableIcon`, the same
  composition mechanism the platform itself uses for icon badges/overlays, so it satisfies
  `ScalableIcon` (and `DarkIconProvider`/`IconWithToolTip`) correctly everywhere, not just this one
  call site.
- 152 tests pass.

## [2.1.1] — Monitor count badge, proper display name, search-friendly description
- **Feature:** the **Multiple Run Monitor tool window now shows a badge** with the number of running
  applications on its stripe button — it grows as apps start, shrinks as they stop and disappears
  when nothing is running, so the count is visible without opening the monitor. Driven by process
  start/stop events (`ExecutionListener` via a `postStartupActivity`), so it stays correct even while
  the tool window is closed.
- **Fix:** the plugin's **display name** in the Marketplace, the installed-plugins list and plugin
  search was showing the artifact id `multiple_run` (with the underscore) instead of **Multiple
  Run** — the Gradle `pluginConfiguration.name` was set to the artifact id and overrode the proper
  name.
- **Docs:** the Marketplace description now leads with a keyword-rich summary (run configurations,
  microservices, monitor memory/CPU/ports/health, .env, docker-compose) for better search, and the
  banner width is capped so it no longer renders oversized in the IDE's install panel.

## [2.1.0] — Load .env files on a plain Play/Debug (Node-based run configurations)
- **Feature:** the `.env`-file loading that used to exist only inside a Multiple Run group is now
  available on any Node-based run configuration (Node.js, npm/pnpm/yarn, Karma, Jest, Mocha —
  anything that is an `AbstractNodeTargetRunProfile`), so running such an app directly with the
  IDE's own Play/Debug loads the file too. An **"Environment file (profile)"** field is added to the
  run configuration's **Configuration** tab (inline, with the other run settings) where you register
  one or more `.env` files as profiles and pick the active one. Precedence is the same as a Multiple
  Run group: the file's variables are the base, the configuration's own "Environment variables"
  field wins on conflicts, and running through Multiple Run still applies the group override on top —
  all unchanged.
- **Feature:** when more than one `.env` profile is configured, a **switch button** (the Multiple
  Run env icon) appears on the run toolbar of that app, next to Rerun/Stop. It opens a movable dialog
  listing the profiles by file name (full path as tooltip), the active one marked "(active)" — the
  same style as the monitor's Switch Environment dialog — and **restarts that same run** with the
  chosen file (it does not start a second instance).
- **Feature:** the Multiple Run Monitor's **Env column now covers standalone apps** too: an app
  started with a plain Play/Debug shows the active `.env` file name when one is loaded, and its Env
  cell is clickable to view the variables actually loaded into it — the same detail viewer grouped
  apps already have. The standalone app keeps its own icon and stays ungrouped; only the Env column
  is enriched.
- This integration is an **optional module**: it is wired via `<depends optional="true">JavaScript`,
  so it only lights up in IDEs that have the JavaScript plugin (WebStorm, IntelliJ IDEA Ultimate,
  …). In IDEs without it (IDEA Community, PyCharm Community, …) the core plugin loads and works
  exactly as before. Uninstalling the plugin removes the tab, the button and the launch hook
  entirely; the small settings block left in a run configuration's XML is then simply ignored.
- **Build:** the compile target moved from IntelliJ IDEA Community to **Ultimate 2023.3** (which
  bundles the JavaScript + NodeJS plugins) so the Node run-config APIs are available at compile
  time. This does not change which IDEs the plugin installs in — that is still driven by
  since/untilBuild and the `<depends>` — and building Ultimate needs no license. The settings are
  stored per configuration and survive editor `clone()` via copyable user data.
- Settings-model tests added; full suite green.

## [2.0.15] — "Lanes" empty state for the Multiple Run Monitor
- **Feature:** when no process is running, the Multiple Run Monitor now shows the "Lanes" brand
  illustration (dashed board + ghost lanes + an "add" affordance) with a bold title and a
  secondary hint, instead of the table's plain single-line empty text. The graphic comes from
  `brand/empty-state.svg`, trimmed down to just the artwork (its `<text>` lines and CSS
  `@media (prefers-color-scheme: dark)` block don't survive IntelliJ's static SVG icon rasterizer)
  and split into `icons/empty-state.svg`/`empty-state_dark.svg`, matching the light/dark pair
  convention already used for `mark.svg`. The title and subtitle are real `JBLabel`s instead of
  baked-into-the-SVG text, so they pick up the IDE's actual font and theme colors
  (`UIUtil.getContextHelpForeground()` for the subtitle) automatically.
- The monitor panel now swaps between the table and the empty-state panel via a `CardLayout`
  (`MultiplerunMonitorPanel.setItemsKeepingSelection`), driven by whether the latest refresh
  produced any rows - not by `JBTable`'s built-in empty text, which can only render a short line,
  not a custom illustration.
- 145/145 tests pass.

## [2.0.14] — Fix EDT threading crash when a child configuration needs editing first
- **Fix (crash):** `MultiplerunRunnerState.checkRunConfiguration` calls
  `RunDialog.editConfiguration`/`Messages.showYesNoDialog` (modal Swing dialogs, EDT-only) whenever
  a child configuration can't run as-is - not registered/valid yet, or has "Edit configuration
  before run" checked. `checkRunConfiguration` is reached from `runConfigurations()`, and the
  one-by-one delay/wait chaining deliberately re-enters `runConfigurations()` from a background
  pooled thread (`ApplicationManager.executeOnPooledThread`) so the wait doesn't freeze the IDE.
  Combined, that meant the dialog call could happen off the EDT, throwing
  `RuntimeExceptionWithAttachments: Access is allowed from Event Dispatch Thread (EDT) only` and
  aborting the run instead of prompting the user to fix the configuration. This is pre-existing
  code, unrelated to the Kotlin migration or the recent rename/branding work - it only manifests
  for a configuration that genuinely can't run yet, so it went unnoticed until now. Fixed by
  wrapping just the dialog logic in `ApplicationManager.invokeAndWait { ... }`, which blocks
  whichever thread called it (background or EDT) until the dialog finishes on the UI thread.
- 145/145 tests pass.

## [2.0.13] — Fix two regressions found in manual testing of 2.0.12
- **Fix (crash):** creating, applying, or running a Multiple Run configuration could throw
  `NullPointerException: Parameter specified as non-null is null` from
  `MultiplerunRunConfigurationEditor.resetEditorFrom`/`applyEditorTo`. Root cause: the 2.0.8
  Kotlin migration declared these override parameters non-null based on the SDK's own `@NotNull`
  annotation on `SettingsEditor<Settings>`, but `Settings` has a non-null upper bound - Kotlin
  won't let the override accept a nullable parameter *and* won't let the class implement
  `SettingsEditor<MultiplerunRunConfiguration?>` either (confirmed empirically, both rejected by
  the compiler). The platform's composite `SettingsEditor` wrapper chain can still call these with
  a raw null at the JVM level while a brand-new configuration entry is settling, bypassing
  Kotlin's compile-time guarantee the same way a Java caller always could - which is exactly why
  the *original*, decade-old Java implementation declared this parameter `@Nullable` and handled
  it gracefully instead of trusting the type system. Restored that behavior: added
  `-Xno-param-assertions` to the Kotlin compiler options (`build.gradle`) so Kotlin stops
  auto-inserting `Intrinsics.checkNotNullParameter` on this parameter, and put back the original's
  explicit null checks. Verified by disassembling the compiled class - the null-check bytecode now
  matches the original Java exactly, no `Intrinsics` call.
- **Fix (visual):** the Multiple Run configuration type icon rendered at 2.5x its intended size
  everywhere it appeared (the "Add New Configuration" list, the run/debug configuration switcher) -
  `MultiplerunIcons.Mark` (added in 2.0.12) accidentally reused `pluginIcon.svg`, which must
  declare `width="40" height="40"` for the Settings > Plugins list; `Icon.getIconWidth()`/
  `getIconHeight()` read that declared size, not the SVG's `viewBox`. Added a dedicated
  `icons/mark.svg` (+ `mark_dark.svg`) declaring the correct 16x16 size, same artwork.
- Not fixed, working as documented: the banner image in the Marketplace description (added in
  2.0.12) shows broken when testing a local/unmerged build - it's loaded from a
  `raw.githubusercontent.com` URL pointing at `mainline`, which only resolves once this branch is
  actually merged there.

## [2.0.12] — New visual identity ("Lanes")
- New icon set replacing the generic platform (`AllIcons.*`) icons everywhere the plugin shows its
  own branding or an action that already existed: the plugin icon, the Multiple Run configuration
  type icon (now reused from `pluginIcon.svg` instead of `AllIcons.Actions.Rerun`), the Monitor
  tool window icon, and the Refresh / Restart / Restart All / Stop / Stop Multiple Run / Show
  Columns / Switch Environment / Memory Analysis actions, the docker-compose import button, and
  the Processes/Logs tab icons. New `MultiplerunIcons.kt` holds the icon constants (same pattern as
  the platform's own `AllIcons`), loaded via `IconLoader` from `src/main/resources/icons/`.
- Deliberately **not** swapped: actions with no clear match in the new icon set (Force Kill, Kill
  Process on Port, env profile/preset remove/save, log Clear/Scroll, the standalone-app and
  fallback icons) - forcing a mismatched icon would be worse than keeping the platform default.
- Moved the non-runtime brand assets (`BRAND_GUIDELINES.md`, `banner.png`/`.svg`, `logo.svg`,
  `spinner.svg`, `empty-state.svg`, `preview.html`) out of `src/main/resources/META-INF/` into
  `/brand` at the repo root, so they document the project without shipping inside the plugin
  `.zip`. `spinner.svg` is SMIL-animated (`<animate>`) - IntelliJ's `IconLoader` rasterizes SVG to
  a static frame, so it isn't usable as a real Swing `Icon`; it stays a docs/preview-only asset.
- Docs: added the new logo to the top of the README and the wiki's `Home.md` (the wiki's first
  embedded image), and linked `brand/BRAND_GUIDELINES.md` from the README for UI contributors.
  Also fixed a stale claim in the wiki's `Home.md` ("existing configurations keep working") left
  over from before the 2.0.11 id rename - same fix already applied to this file and `plugin.xml`
  in 2.0.11, just missed in the wiki back then since it's a separate repository.
- No functional change - actions, shortcuts and behavior are unchanged, only their icons.

## [2.0.11] — Internal: finish the Multirun → Multiplerun rename
- Renamed every one of the fork's own internal identifiers from "Multirun" to "Multiplerun":
  class/file names (`MultirunRunConfiguration` → `MultiplerunRunConfiguration`, etc.), the
  `multirun.iml` module file, and every log message / code comment that referred to this plugin
  ("Multirun" → "Multiple Run" in user-facing text, "multirun" → "multiplerun" in the few lowercase
  runtime identifiers, e.g. `ProgramRunner.getRunnerId()`).
- **Breaking for configurations saved before this release**: the persisted run configuration type
  id changed from `"Multirun"` to `"Multiplerun"`. It had originally been kept as `"Multirun"` on
  purpose, for compatibility with configurations saved by the original third-party Multirun plugin
  - but since this fork has no real users yet, and keeping the same id risks a type-id collision if
  both plugins are ever installed together, the id was changed now while there is nothing to break.
  Run configurations saved with an older version of this plugin need to be recreated.
- Docs: updated the README and the Marketplace description to say "Multiple Run" instead of
  "Multirun" wherever the text refers to this plugin. Left every genuine reference to the original,
  third-party Multirun plugin by Ruslan Khmeliuk untouched (credit line, historical change-notes
  entries, the id-collision explanation above).
- Added the `foojay-resolver-convention` Gradle plugin (`settings.gradle`) so the required Java 17
  toolchain can be auto-downloaded when it isn't already installed locally.
- Verified with a clean build: 145/145 tests pass, 0 failures; the packaged plugin jar has zero
  classes left with a stray "Multirun"-named symbol.

## [2.0.10] — Docs: README revamp + support link
- Reorganized the README's feature sections with short emoji labels for faster scanning, added a
  screenshot near the top, and a punchier opening summary.
- Added a "Support This Project" section (and a matching line in the Marketplace description)
  linking to [buymeacoffee.com/welingtonmonteiro](https://buymeacoffee.com/welingtonmonteiro) for
  anyone who wants to support the plugin's upkeep.
- No functional change.

## [2.0.9] — Kotlin migration: the test suite (and the last Java file, the .form)
- **All 14 remaining JUnit test classes converted from Java to Kotlin** — `AppEnvFileTest`,
  `ComposeImporterTest`, `HealthWatcherTest`, `LogFilterTest`, `MemoryHistoryTest`,
  `MultirunPresetTest`, `MultirunRunnerTest`, `ProcessStatsSamplerTest`,
  `RunConfigurationHelperTest`, `AggregatedLogPanelTest`, `EnvVarsDialogTest`,
  `MultirunMonitorPanelTest`, `MultirunRunConfigurationEditorTest`,
  `MultirunStatusBarWidgetTest`. Same test names, same assertions, same coverage.
- Moved `MultirunRunConfigurationEditor.form` from `src/main/java` to
  `src/main/kotlin/.../ui`, next to its Kotlin class - confirmed with a clean build that the
  GUI-Designer form-to-bytecode instrumentation still discovers and weaves it correctly from
  there. `src/main/java` no longer exists.
- Two Kotlin/JUnit gotchas worth noting: `@Rule` fields need `@JvmField` (JUnit's rule runner
  looks for a public *field*, and a plain Kotlin `val` only generates a private field + getter);
  and `String.split(delimiter)` in Kotlin, unlike Java's `String.split(regex)`, does **not** drop
  a trailing empty element when the string ends with the delimiter - `MemoryHistoryTest` needed
  `.dropLastWhile { it.isEmpty() }` after splitting a CSV that ends with a newline.
- **The entire codebase is now Kotlin** - `src/main` and `src/test` alike. 145 tests still pass.
  No functional change.

## [2.0.8] — Kotlin migration complete: the run configuration editor
- **Twenty-first and last class migrated:** `MultirunRunConfigurationEditor`, the run
  configuration editor UI (table of grouped configurations with inline Memory limit/Ready
  when/Env file columns, environment variables + `.env` profile pickers, execution presets,
  docker-compose import). It stays bound to its existing `MultirunRunConfigurationEditor.form`
  (GUI Designer) - the platform's form-to-bytecode instrumentation weaves `$$$setupUI$$$` into a
  Kotlin class exactly as it did into the Java one (verified by inspecting the compiled `.class`),
  so the panel's layout, widgets and bindings are unchanged. `.form`-bound fields are declared
  `lateinit var` so the instrumentation can assign them directly.
- Public surface preserved: `addEnvProfiles(existing, chosen)` stays a `@JvmStatic` companion
  function so `MultirunRunConfigurationEditorTest` (Java, unmodified) keeps calling it exactly as
  before. 145 tests still pass.
- Two Kotlin-specific fixes worth noting for anyone touching this pattern again: `SettingsEditor`'s
  `resetEditorFrom`/`applyEditorTo` are declared with a non-null settings parameter in the SDK
  bytecode (the original Java code annotated them `@Nullable`, which Java never enforced across
  overrides but Kotlin does - the override now takes a non-null `MultirunRunConfiguration`); and
  string literals built with a leading `+` on the continuation line don't parse in Kotlin (the
  operator must trail the previous line, not lead the next one).
- **`src/main` is now 100% Kotlin** - `src/main/java` contains no source files, only the
  `.form`. Only the test suite (`src/test/java`) remains Java. This closes out the incremental
  Java-to-Kotlin migration started in 2.0.2.

## [2.0.7] — Kotlin migration: launch pipeline, persistence and the monitor
- **Sixteenth through twentieth classes migrated (five at once) - the last batch before the
  GUI-form editor:**
  - `MultirunRunner` — the `GenericProgramRunner` launcher (executor matching, settings editor
    hookup).
  - `AggregatedLogPanel` — the `docker compose logs -f`-style aggregated Logs tab, including the
    ANSI/CSI escape-sequence parser and the color palette.
  - `MultirunRunnerState` — the launch pipeline: one-by-one chaining with readiness gates
    (port/http/log), per-app env resolution, crash-restart (`ProgramRunner.Callback`), console
    tab pin/hide/mark-failed handling.
  - `MultirunRunConfiguration` — persistence (`readExternal`/`writeExternal`), the `Preset` and
    env-profile model, `getState`/`createStateForApps`, `checkConfiguration`. Extends
    `RunConfigurationBase<RunConfigurationOptions>` (the raw-typed Java supertype needed an
    explicit type argument in Kotlin).
  - `MultirunMonitorPanel` — the monitor tool window: the `Row`/`ProcessSnapshot` model, all
    table columns and cell renderers, the batch/row actions, per-app and batch environment
    switching, the memory sparkline.
- Public surfaces are **unchanged**: every getter/setter/`isX` method keeps its exact Java name
  (no Kotlin property sugar), `MultirunMonitorPanel.Row`'s constructor stays public and
  positional (constructed directly from Java test code), nested types
  (`MultirunRunConfiguration.Preset`) keep their fields `@JvmField`-accessible. 145 tests still
  pass, including `MultirunMonitorPanelTest` (which builds `Row` instances directly),
  `AggregatedLogPanelTest`, `MultirunPresetTest` and `MultirunRunnerTest` - all unmodified.
- Two API-compatibility notes for the curious: the platform's `RunManager` class is itself
  Kotlin-sourced, so calling `RunManager.getInstance(project).allConfigurationsList` needs Kotlin
  property syntax rather than `.getAllConfigurationsList()`; and `RunConfigurationBase` (extended
  with a raw type in the old Java code) now takes an explicit `RunConfigurationOptions` type
  argument. Neither changes behavior.
- Only the run-config editor (bound to a GUI-Designer `.form`) stays Java - migrating it means
  rewriting the UI in Kotlin UI DSL, deferred as its own effort. No functional change in this
  release.

## [2.0.6] — Kotlin migration: five UI/watcher classes
- **Eleventh through fifteenth classes migrated (five at once):**
  - `EnvVarsDialog` — the read-only viewer of the environment variables loaded for one app at
    launch (filter + mask-values), including the pure `filterByName`/`maskValue` helpers.
  - `MultirunProcessRegistry` — the per-project registry of running apps (`register`/`unregister`/
    `getEntries`/`snapshot`/`findMetadataByName`/`pidOf`) that backs the monitor, the status bar
    widget and the memory/health watcher.
  - `MultirunStatusBarWidget` — the status bar summary (apps · memory · unhealthy count).
  - `MemoryLimitWatcher` — the background watcher raising memory-limit, health and sustained-CPU
    notifications independently of the monitor tool window.
  - `MemoryChartDialog` — the full-session memory chart (custom-painted, labeled axes), the leak
    analysis tab with a per-process breakdown, and CSV/text export.
- Public surfaces are **unchanged**: nested types (`MultirunProcessRegistry.Entry`) stay
  field-accessible (`@JvmField`), static entry points stay static (`@JvmStatic`), package-private
  Java helpers (`EnvVarsDialog.filterByName`/`maskValue`, `MultirunStatusBarWidget.ID`) become plain
  public Kotlin members (Kotlin has no package-private visibility) - a safe, harmless widening. The
  `ChartComponent` inner class keeps its non-static (`inner`) binding to the dialog so it still
  reads `samples` directly. 145 tests still pass (`EnvVarsDialogTest`, `MultirunStatusBarWidgetTest`,
  `HealthWatcherTest`, and the `MemoryLimitWatcher.isNearLimit` cases in `ProcessStatsSamplerTest`
  all compile and pass against the new Kotlin classes unmodified). No functional change.

## [2.0.5] — Kotlin migration: five IDE-glue registration classes
- **Sixth through tenth classes migrated (five at once):** the platform-registered glue classes
  that had little logic of their own now live in Kotlin:
  - `MultirunConfigurationType` — the `ConfigurationType`/`SimpleConfigurationType` that creates
    the template `MultirunRunConfiguration` (the persisted type id stays `"Multirun"`, unchanged).
  - `ShowMultirunMonitorAction` — the Run-menu action that activates the monitor tool window.
  - `MultirunMonitorToolWindowFactory` — registers the "Multiple Run Monitor" tool window and its
    two tabs (Processes, Logs); `TOOL_WINDOW_ID` stays a `public static final` constant.
  - `MultirunStatusBarWidgetFactory` — registers the status bar widget.
  - `StopRunningMultirunConfigurationsAction` — the "stop all" action *and* the per-project
    registry of processes it started (`addProcess`/`removeProcess`/`stopAll`/`stopProcessesOf`),
    used throughout the launch pipeline and the monitor; `ACTION_ID` stays a `public static final`
    constant.
- Class names, ids and every public method signature are **unchanged**, so the `plugin.xml`
  registrations and every Java caller (the launch pipeline, the monitor panel) keep working
  untouched. 145 tests still pass (none of these five had dedicated unit tests before - they are
  IDE-registration glue with no pure logic to isolate). No functional change.

## [2.0.4] — Kotlin migration: RunConfigurationHelper + ProcessStatsSampler
- **Fourth and fifth classes migrated (two at once):**
  - `RunConfigurationHelper` — dotenv parsing/merging (`parseEnvFile`, `withEnvFile`,
    `withAppEnvFile`, `mergeEnvData`, `withMemoryLimit`), the "Ready when" condition parser
    (`ReadyCondition`, `parseReadyCondition`, `isPortOpen`, `isHttpHealthy`), the loop-guard
    (`containsLoopies`) and the environment-override reflection fallbacks
    (`withEnvironmentOverride` and its `CommonProgramRunConfigurationParameters`/`getEnvData`/
    `getEnvs`/`getRunSettings` strategies) used for run configuration types (e.g. Node.js) that
    don't implement the common IntelliJ SDK interface.
  - `ProcessStatsSampler` — `ps`/PowerShell/`lsof`/`/proc` output parsing, process-tree
    aggregation and the docker-stats-style formatting (`formatMemory`, `formatUptime`,
    `memoryPercent`) that feed the monitor, the status bar widget and the memory chart.
- Both public surfaces are **unchanged**: nested types (`ReadyCondition`+`Type`, `Stats`) stay
  nested with `@JvmField` members, every entry point stays static via `@JvmStatic`, so all Java
  callers (the launch pipeline, the monitor panel, the memory-limit watcher, the status bar
  widget, the memory chart dialog, the run-config editor) keep calling them untouched, and the
  existing `RunConfigurationHelperTest`/`ProcessStatsSamplerTest` (including the real-socket and
  real-`ps` integration tests) stay green. 145 tests still pass. No functional change.

## [2.0.3] — Kotlin migration: docker-compose parser
- **Third class migrated:** `ComposeImporter` (the `docker-compose.yml` reader — `mem_limit` and
  `deploy.resources.limits.memory`, `env_file`, `depends_on`, `ports`, plus the depth-first
  topological ordering of services) is now Kotlin. Pure logic, no IDE dependency, fully unit-tested.
- Its public surface is **unchanged**: the nested `Service` type stays nested with field-accessible
  members (`@JvmField`), the entry points stay static (`@JvmStatic`), so the run-config editor's
  *Import from docker-compose.yml* keeps calling it untouched and the existing Java
  `ComposeImporterTest` stays green. 145 tests still pass. No functional change.

## [2.0.2] — Kotlin migration: memory-history / leak-analysis helper
- **Second class migrated:** `MemoryHistory` (the per-app memory-trend model — `Sample`,
  `Analysis`, the `Trend` verdict, least-squares slope / R² leak detection, the human-readable
  summary and the CSV export) is now Kotlin. Pure logic, no IDE dependency, fully unit-tested.
- Its public surface is **unchanged**: the nested `Sample`/`Analysis`/`Trend` types stay nested and
  their fields stay field-accessible (`@JvmField`), the static helpers stay static (`@JvmStatic`),
  so the monitor panel and the memory chart dialog (still Java) keep calling it untouched and the
  existing Java `MemoryHistoryTest` stays green. 145 tests still pass. No functional change.

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
