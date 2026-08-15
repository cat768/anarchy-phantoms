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
| **Minecraft version** | 1.21.9+ (built against current Paper API; see [Compatibility](#compatibility)) |
| **Java** | 21+ |

> Spigot/Bukkit (non-Paper) are **not** supported — the plugin uses Paper-specific API (`EntitySoundEvent`) to control phantom sounds.

## Installation

1. Download the latest `anarchy-withers-<version>.jar` from [Releases](../../releases) (or build it yourself — see below).
2. Drop it into your server's `plugins/` folder.
3. Start/restart the server. A default `config.yml` will be generated under `plugins/AnarchyWithers/`.
4. Adjust `config.yml` to taste, then run `/anarchywithers reload` (or `/aw reload`) to apply changes without a restart.

## Building from source

```bash
git clone https://github.com/cat768/anarchy-phantoms.git
cd anarchy-phantoms
mvn clean package
```

The built jar will be in `target/anarchy-withers-<version>.jar`.

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
| `/anarchywithers reload` (alias `/aw reload`) | Reloads `config.yml` without a server restart | `anarchywithers.admin` | op |

## Compatibility

This plugin targets whatever the **current stable Paper API** is, and aims to stay forward-compatible as Minecraft/Paper ship new versions:

- Starting in 2026, Mojang moved to year.drop versioning (e.g. `26.1`, `26.2`, ...) instead of `1.x`, and Paper follows the same scheme for Java Edition builds. Bedrock version numbers (e.g. `26.40`) are a **separate** numbering track and don't correspond 1:1 with Java/Paper releases — don't match them up when picking a Paper API version.
- The `paper-api` dependency version in `pom.xml` and the `api-version` in `plugin.yml` should be bumped to track the latest Paper release as new versions ship. Paper API is generally backward compatible across minor releases, so builds against a recent API version will typically keep working on newer server versions without code changes.
- **Folia support**: `folia-supported: true` is already set in `plugin.yml`. The plugin only uses standard Bukkit event listeners and per-entity persistent data (no global schedulers, no cross-region state), so it runs correctly under Folia's regionized threading model as well as on standard Paper.

If you update the target Minecraft version, the recommended process is:

1. Bump `paper-api` in `pom.xml` to the latest `X.Y.Z-R0.1-SNAPSHOT` from the [PaperMC repository](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/).
2. Bump `api-version` in `plugin.yml` to match (Paper only requires the major.minor, e.g. `'1.21'` or the new year-based equivalent once Paper documents it for plugin.yml).
3. Run `mvn clean package` and smoke-test spawning/combat/sound behavior in The End.

## Project structure

```
src/main/java/com/anarchywithers/phantomcontrol/
├── AnarchyWithersPlugin.java       # Plugin entrypoint, command handling, listener registration
├── PluginSettings.java             # Typed, reloadable view over config.yml
├── PhantomSpawnListener.java       # Restricts natural spawns to The End + valid surface blocks
├── PhantomBehaviorListener.java    # Keeps phantoms passive until they take player damage
├── PhantomProvocationTracker.java  # Per-entity "provoked" state via PersistentDataContainer
└── PhantomSoundListener.java       # Suppresses ambient phantom sounds until provoked
```

## License

No license has been specified for this project yet.