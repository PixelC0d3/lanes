Multiple Run
============

IntelliJ-based IDE plugin to execute multiple Run Configurations in a single click.
Fork of the original [Multirun](https://github.com/rkhmelyuk/multirun) plugin.

IntelliJ ships with a built-in [Compound run/debug configuration](https://www.jetbrains.com/help/idea/run-debug-configuration-compound.html),
but if you need more flexibility and control over *how* the configurations are executed
(order, delay, tab handling, marking failures, etc.) then Multirun is for you.

You create a **Multirun** run configuration, add the run configurations you want to it, pick a
few options, and run everything at once — as a group, in parallel or one-by-one.

## Features

### Grouping
- Group any number of run configurations into a single **Multirun** configuration and start
  them with one click.
- **Nesting / composite configurations**: a Multirun configuration can contain other Multirun
  configurations, so you can build a "master" configuration that starts several groups at once.
- **Loop protection**: the editor and runner detect and prevent cycles (A contains B, B contains
  A), so you can nest freely without breaking anything.

### Execution modes
- **Parallel** — start all configurations at the same time.
- **One by one** — start the next configuration only after the previous one has started. This is
  useful when "Before launch" tasks would otherwise run in parallel and interfere with each other.

### Delay between configurations (one-by-one mode only)
The delay field accepts fractional seconds (e.g. `0.5`) and behaves as follows:

| Delay value | Behavior |
|------------|----------|
| `0`        | Start the next configuration immediately after the previous one has started. |
| positive (e.g. `2.0`) | Wait up to N seconds before starting the next one (starts earlier if the current process finishes first). A progress bar shows the countdown. |
| negative (e.g. `-1`)  | **Serial execution** — wait until the current process *completes* before starting the next one. |

The delay field is only enabled when *Start configurations one by one* is checked, and the value
is parsed using the current locale (so `0,5` works on locales that use a comma as the decimal separator).

### Environment variables override
- Define environment variables directly on the Multirun configuration — they are applied to
  **every** configuration in the list, overriding the child's own variables with the same name.
- Uses the standard IDE dialog (add variables one by one, paste, and toggle
  *Include system environment variables*).
- Overrides propagate through nested Multirun configurations too.
- **Environment file**: point the *Environment file* field to a `.env` file (browse button or
  type the path — relative paths are resolved against the project root). The file uses the usual
  dotenv format: `KEY=VALUE` lines, `#` comments, optional `export` prefix and quoted values.
  It is re-read on every run, so editing the file requires no configuration changes. Variables
  from the table above win over the file on conflicts.
- Works with configuration types that expose environment variables (Node.js, npm, Java
  Application, etc.); other types run unchanged.

### Per-application memory limit
- The configurations list is a table with an editable **Memory limit (MB)** column — click the
  cell next to an application and type the cap (empty = no limit). The process-level analog of
  Docker's `mem_limit`.
- Applied at launch through the environment: `NODE_OPTIONS --max-old-space-size=<MB>` (Node.js)
  and `JAVA_TOOL_OPTIONS -Xmx<MB>m` (JVM); existing options in those variables are preserved.
- Note: unlike Docker, a plain OS process has no enforced swap/reservation limits — this caps the
  runtime heap, which is what usually matters for Node/JVM apps in development.

### Console tab handling
- **Mark the tab of a failed configuration** — adds an alert icon to the tab of any configuration
  that exits with a non-zero status, so you can spot failures at a glance.
- **Close tab of a successfully completed configuration** — leaves only the failed tabs open.
  Handy when running lots of tests and you only care about the failures.
- **Re-use tabs of succeeded configurations** — reuse the console tab for successful runs (like the
  default IDE behavior), while failed runs keep their own tab.
- **Re-use tabs of failed configurations** — optionally reuse tabs for failed runs too.
- Running configurations are marked with a `*` in the tab title while they are still running.
- When tab re-use is disabled, tabs are pinned so they are not recycled.
- **Save console logs to folder** — point the *Save console logs to* field to a folder and the
  console output of every configuration in the list is also written there as
  `<configuration name>.log`, using the IDE's standard *save console output to file* mechanism
  (the same one behind the Logs tab of individual run configurations). Relative paths are
  resolved against the project root.

### Restarting and stopping
- **Restart on rerun** (enabled by default) — running a Multirun that is already running first stops
  the processes it started before, waits for them to terminate, and then starts everything again —
  just like the built-in Compound configuration. No need to stop the services manually before
  rebuilding/rerunning. Only the processes of the restarted Multirun are stopped; other running
  Multirun groups are untouched. Can be disabled per configuration with the
  *Restart running configurations before starting* option.
- **Stop Multiple Run** action stops all running configurations started by the plugin (and cancels
  any that are still queued to start).
- Available from **Run → Stop Multiple Run** and via shortcut:
  - Windows/Linux: <kbd>Shift</kbd>+<kbd>Alt</kbd>+<kbd>K</kbd>
  - macOS: <kbd>Control</kbd>+<kbd>Alt</kbd>+<kbd>K</kbd>

### Supported executors
Multirun configurations can be launched with:
- **Run**
- **Debug**
- **Run with Coverage**
- **Profiler**
- **JRebel** (Run and Debug)

## Supported IDEs

Multirun only depends on the platform and language modules, so it works in IntelliJ IDEA and the
other IntelliJ-based IDEs: **WebStorm, PyCharm, PhpStorm, RubyMine, GoLand, CLion, Rider, AppCode**, etc.

Compatible with builds since `233` (**2023.3** and newer).

## Installation

### From the JetBrains Marketplace
`Settings/Preferences → Plugins → Marketplace`, search for **Multirun**, install and restart.

### From disk (a locally built `.zip`)
`Settings/Preferences → Plugins → ⚙ (gear icon) → Install Plugin from Disk…`, select the
`multirun-<version>.zip` file (see *Building from source* below), then restart the IDE.

## Usage

1. `Run → Edit Configurations…`
2. Click **+** and add a new **Multiple Run** configuration.
3. Use the list toolbar to add the run configurations you want to launch.
4. Pick the options you need (parallel vs one-by-one, delay, tab handling, marking failures, …).
5. Apply and run the Multirun configuration like any other configuration.

Tip: **Multirun + Before Launch tasks** unlocks even more scenarios — for example, chaining setup
tasks before a group of applications or tests.

## Building from source

The project builds with the Gradle IntelliJ Plugin. You need a JDK 17+ (the JetBrains Runtime that
ships inside any recent IntelliJ-based IDE works well).

```bash
# point JAVA_HOME at a JDK 17+ (e.g. a bundled JBR)
export JAVA_HOME=/path/to/jbr

# build the installable plugin zip -> build/distributions/multirun-<version>.zip
./gradlew buildPlugin

# or launch a sandbox IDE with the plugin pre-installed, for quick testing
./gradlew runIde
```

The plugin version is managed from `build.gradle` (`version = '…'`) and injected into
`plugin.xml` at build time by `patchPluginXml`.

## Credits By

Originally created by **Ruslan Khmeliuk** ([rkhmelyuk/multirun](https://github.com/rkhmelyuk/multirun)).
See the original [wiki](https://github.com/rkhmelyuk/multirun/wiki) for additional documentation.

## License

See [license.txt](license.txt).
