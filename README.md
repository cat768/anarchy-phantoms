# Anarchy Phantoms

A lightweight Paper/Folia plugin that brings Phantoms to anarchy-style End servers, 2b2t-style: phantoms are **actively spawned** above players in The End (vanilla has no natural spawn cycle there at all), only above end stone or chorus blocks, and stay passive and silent until a player attacks one.

## Behavior

- **Active End spawning** — Vanilla phantoms only have a natural spawn cycle in the Overworld (gated by 3 sleepless in-game days). This plugin actively spawns phantoms above online players in The End on a per-player timer, mirroring 2b2t's "Phantoms In The End" behavior. Not gated by sleep/insomnia — The End has no beds or day/night cycle, so that stat is meaningless there.
- **Overworld spawns blocked** — Natural Overworld phantom spawns are disabled outright (configurable).
- **Valid surface required** — Phantoms only spawn above a real, known end stone, chorus plant, or chorus flower surface (configurable materials and search depth), never over open void.
- **Per-player spawn limits** — Spawn chance, check radius, and a max-phantoms-per-player cap are all configurable, so spawning scales instead of flooding a player.
- **Passive until attacked** — Phantoms won't target or swoop at players on their own. A phantom only becomes hostile toward players after a player deals damage to it.
- **Silent until provoked** — Ambient screech/flap/swoop sounds are suppressed for phantoms that haven't been attacked yet. Once provoked, sounds play normally.
- **Per-entity, not per-server** — Provocation is tracked on the individual phantom (via its persistent data), so one aggravated phantom doesn't flip every phantom on the map.
- **Deliberate spawns are untouched** — Spawns from spawn eggs, commands, or other plugins are left alone; only natural/environmental spawns are governed, so admins keep full control when they want it.
- **Folia-safe** — Active spawning uses a per-player `EntityScheduler` (via `Player#getScheduler()`), not a global scheduler sweep, so it follows each player across regions correctly under Folia.

All of the above is configurable — see [Configuration](#configuration).

## Requirements

| | |
|---|---|
| **Server software** | [Paper](https://papermc.io/) or [Folia](https://papermc.io/software/folia) |
| **Minecraft version** | 1.21.9+ (compiled against `paper-api` 1.21.11, `api-version: '1.21'` keeps it working across the whole 1.21.x line; see [Compatibility](#compatibility)) |
| **Java** | 21+ |

> Spigot/Bukkit (non-Paper) are **not officially supported** — the plugin is built against the Paper API and ships with `folia-supported: true`, so it's tested only on Paper/Folia.

## Installation

1. **[⬇ Download the latest jar](../../releases/latest/download/anarchy-phantoms-latest.jar)** — always the most recent build off `main`, overwritten on every push. That link always points at the current `latest` release, no clicking through [Releases](../../releases) required.

   Need something else instead?
   - **Pin a specific commit:** grab `anarchy-phantoms-git-<sha>.jar` from that commit's [permanent release](../../releases) — immutable, never overwritten.
   - **Build it yourself:** see [Building from source](#building-from-source) below.

   Every release is only published after it passes CI smoke tests booting a real server (see [Compatibility](#compatibility)), so anything published here is known to enable cleanly.
2. Drop it into your server's `plugins/` folder.
3. Start/restart the server. A default `config.yml` will be generated under `plugins/AnarchyPhantoms/`.
4. Adjust `config.yml` to taste, then run `/anarchyphantoms reload` (or `/ap reload`) to apply changes without a restart.

## Building from source

```bash
git clone https://github.com/cat768/anarchy-phantoms.git
cd anarchy-phantoms
mvn clean package
```

The built jar will be in `target/anarchy-phantoms-<version>.jar`.

## Testing

```bash
mvn test
```

A [MockBukkit](https://docs.mockbukkit.org) + JUnit 5 behavioral suite (34 tests across 6 classes, `src/test/java/`) fires real Bukkit events through the plugin's actual registered listeners and asserts on the resulting entity/event state, rather than just checking that the plugin boots. It covers:

- **Spawn gating** (`PhantomSpawnListenerTest`) — overworld spawns vetoed, End spawns allowed only above a valid surface, void/no-surface spawns vetoed, spawn eggs/commands left ungoverned, non-phantom entities ignored.
- **Passive-until-attacked behavior** (`PhantomBehaviorListenerTest`) — unprovoked phantoms can't target players, provoked ones can, non-player damage doesn't provoke, provocation is per-entity.
- **Silent-until-provoked sounds** (`PhantomSoundListenerTest`) — silent on spawn, un-silenced on provocation.
- **Provocation state** (`PhantomProvocationTrackerTest`) — the PDC-backed provoked/provoked-at-tick flag in isolation.
- **Config edge cases** (`PluginSettingsTest`) — `surface-check-depth` and `spawn-chance` clamping, unknown-material handling, debug runtime-override precedence.
- **Active End spawning** (`PhantomEndSpawnerTest`) — spawns occur/don't occur per `spawn-chance` and `end-spawning.enabled`, the per-player cap is respected, spawning stops once a player leaves The End.

This suite is wired into CI as the `unit-test` job and gates the rest of the pipeline — see [CI pipeline](#ci-pipeline).

## Configuration

Default `config.yml`:

```yaml
phantoms:
  # If true, phantoms can never spawn in the overworld (NORMAL environment).
  block-overworld-spawns: true

  # If true, phantoms can only spawn in The End (THE_END environment).
  only-spawn-in-end: true

  # Blocks a phantom is allowed to spawn directly above.
  # Uses Bukkit Material names.
  allowed-surface-blocks:
    - END_STONE
    - CHORUS_PLANT
    - CHORUS_FLOWER

  # How far below the spawn location to look for a valid surface block.
  # Automatically clamped at load time to be no shallower than end-spawning's
  # max height-above-ground (30), so a stale/misconfigured value can't
  # silently veto every actively-spawned End phantom.
  surface-check-depth: 35

  # Phantoms will not target or attack players until a player damages them first.
  passive-until-attacked: true

  # Ambient "screech" sound is suppressed until the phantom has been attacked.
  silence-screech-until-attacked: true

  # How long (in ticks) a phantom stays "provoked" (hostile + vocal) after being
  # attacked, and after which it reverts to passive/silent if left alone.
  # Set to -1 to make provocation permanent for that phantom's lifetime.
  provoked-duration-ticks: 6000

  # Active End spawning. Vanilla has NO natural phantom spawn cycle in The
  # End at all, so without this section nothing ever attempts to spawn a
  # phantom there naturally for the rules above to even apply to.
  end-spawning:
    # Master switch for actively spawning phantoms in The End.
    enabled: true

    # Chance, per player, per check interval (every 10 seconds), that a
    # spawn attempt is made. 0.15 ≈ a spawn attempt on average every ~67s
    # per eligible player.
    spawn-chance: 0.15

    # Radius (in blocks) used both to count existing nearby phantoms and
    # as the general "near this player" range.
    spawn-check-radius: 32.0

    # Per-player cap: stop spawning more phantoms near a player once this
    # many are already within spawn-check-radius of them.
    max-phantoms-per-player: 4

# Console/log-only debug logging of spawn eligibility checks (never sent to
# players in chat). Can also be flipped at runtime — see Commands below.
debug:
  enabled: false
```

## Commands & permissions

All subcommands work under either `/anarchyphantoms` or its shorter alias `/ap`. **The entire command is operator-only** — every subcommand, including `help` and `ver`, requires `anarchyphantoms.admin`. Non-admins can't run or see any of it; Bukkit rejects the command before it even reaches the plugin (via `permission` on the command in `plugin.yml`), and the same check is repeated in code as a backstop.

| Command | Description | Permission | Default |
|---|---|---|---|
| `/ap` or `/ap help` | Lists available subcommands, and tells the sender whether they currently hold `anarchyphantoms.debug`. | `anarchyphantoms.admin` | op |
| `/ap ver` (alias `version`) | Shows the running build's version/commit info, plus a clickable link to the GitHub repo. | `anarchyphantoms.admin` | op |
| `/ap git` | Shows the current build's commit, with its full commit message. | `anarchyphantoms.admin` | op |
| `/ap git info <hash>` | Shows full detail for a specific baked-in commit. | `anarchyphantoms.admin` | op |
| `/ap git history [page]` | Lists baked-in commit history, newest first, 8 per page. | `anarchyphantoms.admin` | op |
| `/ap reload` | Reloads `config.yml` without a server restart. Note: a live `/ap debug` runtime override (if set) is *not* cleared by this — see below. | `anarchyphantoms.admin` | op |
| `/ap debug <on\|off>` | Toggles debug logging at runtime, overriding `debug.enabled` in `config.yml` until the next restart or another `/ap debug` call. Running it with no `on`/`off` argument reports the current state. See [Debug output](#debug-output) below for what this reports and to whom. | `anarchyphantoms.admin` | op |
| `/ap update` | Fetches the latest CI-validated build (the `latest` GitHub release), verifies its SHA-256 checksum, and stages it into `plugins/update/`. Applied on the **next server restart**, not live. | `anarchyphantoms.admin` | op |
| `/ap rollback <hash>` | Fetches the CI-validated build for a specific commit (the `git-<hash>` GitHub release), verifies its SHA-256 checksum, and stages it the same way. Fails cleanly if that hash never passed CI (no matching release exists) or if the checksum can't be verified. | `anarchyphantoms.admin` | op |

Both commands do two independent checks before staging anything: **(1)** a release must exist for the requested tag — CI only publishes a `git-<sha>` release after every smoke-test matrix leg passes, so an invalid or never-tested hash simply 404s; **(2)** the downloaded jar's SHA-256 must match the `.sha256` digest CI published in that same release — this is the actual integrity check on the bytes, and is what protects against a corrupted download, a truncated transfer, or a release whose asset was swapped/edited after the fact. A release with a jar but no matching digest asset, or a digest that doesn't match, is rejected the same way as a 404: nothing is staged, and the reason is reported to the admin.

`anarchyphantoms.debug` remains a separate, independent permission (default `op`) that only controls whether a player sees debug lines in chat while debug mode is on — it does not grant access to any `/ap` subcommand.

### Debug output

When debug mode is on (`debug.enabled` in config, or toggled live via `/ap debug on`), every governed phantom spawn and every passive/aggressive transition is reported, each line timestamped:

```
[AP-DEBUG] [14:32:07] phantom spawned at 120, 68, -340 in world_the_end | cause: End-spawner near player Notch | surface: END_STONE (2 blocks below)
[AP-DEBUG] [14:33:51] phantom turned AGGRESSIVE at 118, 70, -338 | provoked by: player Notch
[AP-DEBUG] [16:13:51] phantom reverted to PASSIVE at 118, 70, -338 | reason: provocation window expired
```

- **Console** always sees debug output when debug mode is on, regardless of permissions.
- **Players** only see it in chat if they hold `anarchyphantoms.debug` — a separate node from `anarchyphantoms.admin`, so debug-spam visibility can be granted per-player without also handing out reload/toggle/update access, and vice versa.
- Spawn reports cover both allowed and vetoed spawns, and attribute the cause (the triggering player for End-spawner spawns, or the raw `SpawnReason` otherwise) plus which surface block the spawn landed on.

## Plan Player Analytics integration

If [Plan Player Analytics](https://github.com/plan-player-analytics/Plan) is installed on the same server, AnarchyPhantoms automatically reports activity stats to it via Plan's DataExtension API - no configuration needed. Plan is a **soft-dependency only**: AnarchyPhantoms works identically with or without it installed.

Reported on each player's Plan page:

| Stat | Description |
|---|---|
| Phantoms Spawned Nearby | Lifetime count of phantoms actively spawned near this player by the End-spawner |
| Phantoms Provoked | How many phantoms this player has provoked (first hit only) |
| Phantoms Active Nearby | Phantoms currently within spawn-check radius, as of the last check cycle |
| Has Provoked a Phantom | Yes/No |

Reported on Plan's server overview page:

| Stat | Description |
|---|---|
| Total Phantoms Spawned | Server-wide lifetime count of End-spawner spawns |
| Total Provocations | Server-wide lifetime count of provocations |
| End-Spawner Success Rate | Share of spawn attempts that resulted in a live phantom (vs. vetoed by the surface/dimension checks) |

These are in-memory counters (see `PhantomStatsTracker`), so they reset on server restart - they're meant to reflect ongoing activity, not a permanent historical log.

## Compatibility

- `pom.xml` compiles against `paper-api` version `1.21.11-R0.1-SNAPSHOT`.
- `plugin.yml` declares `api-version: '1.21'` — Paper's `api-version` field is only ever major.minor granularity, and Paper treats this as "compatible with any 1.21.x server." That's what lets a jar built against 1.21.9 load and run unmodified on newer 1.21.x releases as well, without needing a rebuild for every patch version.
- **Folia support**: `folia-supported: true` is set in `plugin.yml`. The plugin uses standard Bukkit event listeners, per-entity persistent data, and per-player `EntityScheduler` tasks (no global schedulers, no cross-region state), so it runs correctly under Folia's regionized threading model as well as on standard Paper.
- Starting in 2026, Mojang moved to year.drop versioning (e.g. `26.1`, `26.2`, ...) instead of `1.x`, and Paper follows the same scheme for Java Edition builds going forward (1.21.11 was the last `1.x`-style release; see [Paper's project setup docs](https://docs.papermc.io/paper/dev/project-setup/)). Bedrock version numbers (e.g. `26.40`) are a **separate** numbering track and don't correspond 1:1 with Java/Paper releases — don't match them up when picking a Paper API version.

### CI pipeline

CI runs in two stages. A `unit-test` job runs first and gates everything else: it runs the MockBukkit/JUnit behavioral suite (see [Testing](#testing)) via `mvn -B test`, publishes results as a GitHub Checks summary, and must pass before the compile/boot pipeline is even attempted. `unit-test` is also the only job that runs on pull requests — the boot-matrix and publish steps stay push-to-`main`/`workflow_dispatch`-only, so a PR gets fast behavioral feedback (~1 minute) without spinning up real servers or attempting to cut a release from an unmerged head.

Once `unit-test` passes, `.github/workflows/build.yml` builds the jar once, then dynamically discovers every currently-STABLE Paper **and** Folia version (from PaperMC's Fill API) at or above `MIN_MC_VERSION` (currently `1.21.9`) and boots the same jar against every one of them in parallel. Paper 1.21.9 itself is separately pinned into the matrix even though it never had a STABLE Fill build (it's ALPHA-only, superseded same cycle by 1.21.10), since it's the plugin's actual production target.

- Publishing (to both the rolling `latest` release and a permanent `git-<sha>` release) only happens if `unit-test` **and every** smoke-test matrix leg pass.
- `.github/workflows/latest-drift-check.yml` is a safety net that fires after each `build.yml` run: it compares commit timestamps between `main` and whatever the `latest` tag currently points to, and re-dispatches `build.yml` if `latest` ever falls behind (e.g. a publish step silently no-op'd). It never republishes directly itself — it just re-triggers the real pipeline.
- Matrix generation and server boot logic live in `.github/workflows/scripts/generate-matrix.sh` and `.github/workflows/scripts/smoke-test.sh`.

If you want to move the target Minecraft version forward:

1. Bump `paper-api` in `pom.xml` to the desired `X.Y.Z-R0.1-SNAPSHOT` (pre-26.1) or `YY.D.build.N-stable` (26.1+) from the [PaperMC repository](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/) or [Fill](https://fill.papermc.io/).
2. Bump `api-version` in `plugin.yml` to match the new major.minor.
3. Bump `MIN_MC_VERSION` in `.github/workflows/build.yml` if you're intentionally dropping support for older versions — the smoke-test matrix is discovered dynamically, so you generally don't need to hand-list versions.
4. Run `mvn clean package` and smoke-test spawning/combat/sound behavior in The End.

## Project structure

```
src/main/java/com/anarchyphantoms/phantomcontrol/
├── AnarchyPhantomsPlugin.java      # Plugin entrypoint, command handling, listener registration
├── PluginSettings.java             # Typed, reloadable view over config.yml
├── PhantomEndSpawner.java          # Actively spawns phantoms above players in The End (per-player EntityScheduler)
├── PhantomSpawnListener.java       # Restricts natural spawns to The End + valid surface blocks; reports spawns for debug
├── PhantomSpawnCauseTag.java       # Tags a spawned phantom's PDC with a human-readable spawn cause pre-spawn
├── PhantomBehaviorListener.java    # Keeps phantoms passive until they take player damage; reports aggro transitions
├── PhantomProvocationTracker.java  # Per-entity "provoked" state via PersistentDataContainer
├── PhantomSoundListener.java       # Suppresses ambient phantom sounds until provoked
├── PhantomDebugNotifier.java       # Single dispatcher for debug output (console + anarchyphantoms.debug players)
├── PhantomStatsTracker.java        # In-memory spawn/provocation counters, fed to Plan (see below)
├── PlanHook.java                   # Isolated Plan Player Analytics API access (optional soft-dependency)
├── AnarchyPhantomsDataExtension.java # Plan DataExtension: exposes PhantomStatsTracker's counters to Plan's web panel
├── PluginUpdater.java              # Backs /ap update and /ap rollback: fetches/stages CI-validated builds via GitHub Releases
├── BuildInfo.java                  # Baked-in version/commit info + repo URL, shown by /ap ver
└── GitHistory.java                 # Baked-in commit history, shown by /ap git / git info / git history

src/test/java/com/anarchyphantoms/phantomcontrol/  # MockBukkit/JUnit 5 behavioral suite — see Testing
```

## License

No license has been specified for this project yet.