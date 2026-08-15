# Anarchy Phantoms

A lightweight Paper/Folia plugin that reworks vanilla Phantom behavior for anarchy-style servers: Phantoms only spawn in The End, only above end stone or chorus blocks, stay passive until attacked, and stay silent until provoked.

## Behavior

- **End-only spawning** — Phantoms never naturally spawn in the Overworld. Natural spawns are restricted to The End dimension.
- **Valid surface required** — Phantoms only spawn directly above end stone, chorus plant, or chorus flower blocks (configurable, with a configurable search depth below the spawn point).
- **Passive until attacked** — Phantoms won't target or swoop at players on their own. A phantom only becomes hostile toward players after a player deals damage to it.
- **Silent until provoked** — Ambient screech/flap/swoop sounds are suppressed for phantoms that haven't been attacked yet. Once provoked, sounds play normally.
- **Per-entity, not per-server** — Provocation is tracked on the individual phantom (via its persistent data), so one aggravated phantom doesn't flip every phantom on the map.
- **Deliberate spawns are untouched** — Spawns from spawn eggs, commands, or other plugins are left alone; only natural/environmental spawns are governed, so admins keep full control when they want it.

All of the above is configurable — see [Configuration](#configuration).

## Requirements

| | |
|---|---|
| **Server software** | [Paper](https://papermc.io/) or [Folia](https://papermc.io/software/folia) |
| **Minecraft version** | 1.21.9+ (built/CI-tested on 1.21.9, `api-version: '1.21'` keeps it working on newer 1.21.x builds too; see [Compatibility](#compatibility)) |
| **Java** | 21+ |

> Spigot/Bukkit (non-Paper) are **not officially supported** — the plugin is built against the Paper API and ships with `folia-supported: true`, so it's tested only on Paper/Folia.

## Installation

1. Download the latest `anarchy-phantoms-<version>.jar` from [Releases](../../releases) (or build it yourself — see below).
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
  surface-check-depth: 5

  # Phantoms will not target or attack players until a player damages them first.
  passive-until-attacked: true

  # Ambient "screech" sound is suppressed until the phantom has been attacked.
  silence-screech-until-attacked: true

  # How long (in ticks) a phantom stays "provoked" (hostile + vocal) after being
  # attacked, and after which it reverts to passive/silent if left alone.
  # Set to -1 to make provocation permanent for that phantom's lifetime.
  provoked-duration-ticks: 6000
```

## Commands & permissions

| Command | Description | Permission | Default |
|---|---|---|---|
| `/anarchyphantoms reload` (alias `/ap reload`) | Reloads `config.yml` without a server restart | `anarchyphantoms.admin` | op |

## Compatibility

This plugin is currently built and CI-tested against **Paper 1.21.9**:

- `pom.xml` compiles against `paper-api` version `1.21.9-R0.1-SNAPSHOT`.
- `plugin.yml` declares `api-version: '1.21'` — Paper's `api-version` field is only ever major.minor granularity, and Paper treats this as "compatible with any 1.21.x server." That's what lets a jar built against 1.21.9 load and run unmodified on newer 1.21.x releases (1.21.10, 1.21.11, ...) as well, without needing a rebuild for every patch version.
- The CI workflow (`.github/workflows/build.yml`) boots a real Paper 1.21.9 server with the freshly built jar on every push and fails the build if the plugin doesn't reach "Done" and enable cleanly — so `pom.xml`'s Paper API version and the workflow's `TARGET_MC_VERSION` should always be kept in sync with each other.
- **Folia support**: `folia-supported: true` is already set in `plugin.yml`. The plugin only uses standard Bukkit event listeners and per-entity persistent data (no global schedulers, no cross-region state), so it runs correctly under Folia's regionized threading model as well as on standard Paper.
- Starting in 2026, Mojang moved to year.drop versioning (e.g. `26.1`, `26.2`, ...) instead of `1.x`, and Paper follows the same scheme for Java Edition builds going forward (1.21.11 was the last `1.x`-style release; see [Paper's project setup docs](https://docs.papermc.io/paper/dev/project-setup/)). Bedrock version numbers (e.g. `26.40`) are a **separate** numbering track and don't correspond 1:1 with Java/Paper releases — don't match them up when picking a Paper API version.

If you want to move the target Minecraft version forward:

1. Bump `paper-api` in `pom.xml` to the desired `X.Y.Z-R0.1-SNAPSHOT` (pre-26.1) or `YY.D.build.N-stable` (26.1+) from the [PaperMC repository](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/) or [Fill](https://fill.papermc.io/).
2. Bump `api-version` in `plugin.yml` to match the new major.minor.
3. Bump `TARGET_MC_VERSION` in `.github/workflows/build.yml` to the same version so the CI smoke test actually boots against what the jar was compiled for.
4. Run `mvn clean package` and smoke-test spawning/combat/sound behavior in The End.

## Project structure

```
src/main/java/com/anarchyphantoms/phantomcontrol/
├── AnarchyPhantomsPlugin.java      # Plugin entrypoint, command handling, listener registration
├── PluginSettings.java             # Typed, reloadable view over config.yml
├── PhantomSpawnListener.java       # Restricts natural spawns to The End + valid surface blocks
├── PhantomBehaviorListener.java    # Keeps phantoms passive until they take player damage
├── PhantomProvocationTracker.java  # Per-entity "provoked" state via PersistentDataContainer
└── PhantomSoundListener.java       # Suppresses ambient phantom sounds until provoked
```

## License

No license has been specified for this project yet.