# Realty

Realty is a plugin for [Paper](https://papermc.io/) Minecraft servers that allows you to put up [WorldGuard](https://enginehub.org/worldguard/) regions for sale or lease. You can collect rent, hold auctions, create subregions to rent out to other players, and place offers on other players' regions through one simple interface.

## Requirements

- **Paper** 1.21.8+
- **Java** 21
- **MariaDB/MySQL database** to store region data
- **Vault** and a Vault-compatible economy
- **WorldGuard amd WorldEdit** (required)
- **Essentials** (optional)

Built on the [Hibernia framework](https://github.com/ParadauxIO/hibernia-framework) (commands,
configuration, messages, dialogs and dependency injection), currently consumed from the
[MCCitiesClone fork](https://github.com/MCCitiesClone/hibernia-framework).

## Build

From the repository root:

```bash
./gradlew :realty-paper:shadowJar
```

Install the JAR from `realty-paper/build/libs/` whose name ends with `-all.jar`.

Other artifacts:

```bash
./gradlew :realty-paper-plan-extension:shadowJar
```

## Modules

| Module | Role |
|--------|------|
| `realty-api` | Public API surface |
| `realty-common` | Shared logic and database access |
| `realty-paper` | Main Paper plugin |
| `realty-paper-plan-extension` | Optional [Plan](https://github.com/plan-player-analytics/Plan) integration |

## Upgrading from 1.4.9

Configuration keeps its current layout: `settings.yml`, `database.yml`, `profiles.yml`,
`region-tags.yml` and `taxes.yml` are read exactly as before, and newly shipped keys are merged
into your files on first start without disturbing your values, ordering or comments.

Messages move from `messages.yml` to `messages.properties`. **Your edits are carried across
automatically** on first start: the file is converted in place, placeholders are rewritten from
`<name>` to `{name}`, and the original is kept as `messages.yml.migrated`. Nothing is overwritten
if `messages.properties` already exists. To start from the shipped text instead, delete
`messages.properties` and let it be written fresh.

Commands and permissions are unchanged — every `/realty` command, its arguments, its flags and its
permission node are the same. `/realty help` reads from your messages file as before.

To roll back, restore the 1.4.9 jar and rename `messages.yml.migrated` back to `messages.yml`; the
other configuration files were never modified.

## Documentation

### Getting Started

For detailed setup instructions, visit the [Installation Guide](https://github.com/MCCitiesNetwork/realty/wiki/Installation).

For player, staff, and server-owner guides, visit the [GitHub wiki](https://github.com/MCCitiesNetwork/realty/wiki).
