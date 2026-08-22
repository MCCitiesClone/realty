# Hibernia Framework Migration Plan

**Goal:** Migrate Realty from its current stack (Incendo Cloud commands, Configurate configuration, `plugin-infrastructure` MessageContainer, hand-rolled Paper Dialog API screens, 937 lines of manual constructor wiring in `Realty.java`) onto `io.paradaux:hibernia-framework`, **preserving every existing feature and every operator-facing behaviour**.

**Branch:** `feat/hibernia-framework-migration` (created off `main` @ `365986e`).

**Framework version:** `io.paradaux:hibernia-framework:1.1.0` (latest release; confirmed present at `https://repo.paradaux.io/releases`). Java 21, Paper 1.21.6+ — matches Realty's existing targets exactly.

---

## Scope decisions (from Evan, 2026-08-22)

| Axis | Decision |
|---|---|
| Cloud flags | Add `@Flag` support to Hibernia via a **fork**, preserving syntax *and* tab-completion |
| Configuration | **All** config moves to Hibernia's configurator (requires extending it) |
| Messages | Migrate to `messages.properties` **plus** a one-shot converter that preserves operator edits |
| Dialogs | Migrate **both** `SearchDialog` and `SubregionDialog` to Usher |
| Fork | `MCCitiesClone/hibernia-framework`. **No upstream PRs to ParadauxIO without explicit per-PR permission.** |

### Open assumption — needs a yes/no before Phase 2

Realty has five operator-facing config files (`settings.yml`, `database.yml`, `profiles.yml`, `region-tags.yml`, `taxes.yml`); Hibernia's `ConfigurationLoader` reads only `config.yml`. **This plan assumes we extend the configurator with a per-file `@ConfigurationComponent(file = "profiles.yml")` attribute**, so all five files keep their current names, contents and comments, and the upgrade is a no-op for every existing server. The alternative — consolidating into one ~900-line `config.yml` plus a merger — is a larger operator-facing break for less upstream work. Flagging rather than blocking; Phases 0–1 are unaffected either way.

---

## Architecture after migration

```
Realty.onLoad()   → data folder, config files copied, message file converted if needed
Realty.onEnable() → HiberniaModule.forPlugin(this)
                        .scanConfiguration("io.github.md5sha256.realty.settings")
                        .handlers(...)      ~25 @Command("realty","rl") classes
                        .resolvers(...)     WorldGuardRegion, NamedAuthority, Duration, …
                        .listeners(...)     Sign, SubregionWand, RegionNotification
                        .dialogs(...)       SearchDialogHandler, SubregionDialogHandler
                        .inputBinders(...)  DurationUnit, OccupancyFilter
                        .build()
                  → Guice.createInjector(hibernia, RealtyModule, DatabaseModule, EconomyModule)
                  → CommandManager.registerAll(); ListenerManager.registerAll()
                  → schema migration, executors, ModuleLifecycleManager (unchanged)
```

`Realty.java` shrinks from 937 lines to a lifecycle shell. Business logic (`RealtyPaperApiImpl`, `RealtyBackendImpl`, `PartyService`, `ProfileApplicator`, tax policy) is untouched — Hibernia deliberately covers only the entrypoint tier.

---

## Global constraints

Carried forward from the repo's existing plan conventions:

- **Java 21.** No wildcard imports, no static imports, no inline fully-qualified class names.
- **No behavioural change to `realty-backend`, `realty-backend-api`, or the 47 event classes in `realty-paper-api/.../api/event/`.**
- **`realty-paper-api` is published** (`realty-publish`) and consumed by the Plan extension and both adapters — its public surface must stay source- and binary-compatible.
- **Every one of the 96 permission nodes in `paper-plugin.yml` keeps its exact node string and default.** `PermissionManifestTest` stays green.
- **Adapter module jars load through a `URLClassLoader` parented to Realty's plugin class loader** and are compiled against non-relocated `plugin-infrastructure` types. Guice/Guava/Reflections relocation must not leak into any type an adapter sees.
- **Do not add JUnit deps or `useJUnitPlatform()`** — `realty-conventions` supplies both.
- Commit after every task. `./gradlew build` green before each commit.

---

## Phase 0 — Framework groundwork (fork)

Three features Hibernia 1.1.0 does not have. Verified by reading the framework source, not just its docs.

- [ ] **T0.1 — Fork and baseline.** Fork `ParadauxIO/hibernia-framework` → `MCCitiesClone/hibernia-framework`. Branch `feat/realty-requirements`. Set version `1.1.0-realty-SNAPSHOT`, publish to `maven.democracycraft.net/snapshots` (already in `realty-conventions` repositories). **No PR to ParadauxIO.**

- [ ] **T0.2 — `@Flag` support in `commander`.**
  *Why:* Realty uses ~20 flags across six commands (`list`, `history`, `search`, `terminate`, `create`, `register`) — `--page`, `--player`, `--event`, `--time`, `--now`, `--price`, `--titleholder`, `--authority`, `--landlord`, `--tags`, `--exclude-tags`, `--min-price`, `--max-price`, `--occupancy`, `--freehold`, `--leasehold`. `CommandManager` has **zero** flag handling; routes are literals, `<req>`, `[opt]` and one terminal greedy string.
  *Shape:* `@Flag("page") Integer page` / `@Flag(value = "now", presence = true) boolean now`, flags parsed as a trailing repeatable `--name value` node set in the Brigadier tree, resolved through the existing `ParameterResolver` registry (so `--player` tab-completes exactly as it does today), validated at registration alongside the existing route conflict checks.
  *Files:* `CommandManager.java`, `RouteInfo.java`, new `annotations/Flag.java`, `CommandManagerTest`.

- [ ] **T0.3 — Nested configuration support in `configurator`.**
  *Why:* `profiles.yml` is a map of region-state → `{priority, flags: Map<String,String>, sign: {lines: List, right-click-commands: List, left-click-commands: List}}` plus a `grouped` list of objects; `region-tags.yml` is a list of objects with nested permission blocks; `taxes.yml` has a `rules` list of objects. `ConfigurationProcessor` supports only `String`, numerics, `boolean`, `List<String>` and enums.
  *Shape:* nested `@ConfigurationComponent` types, `List<T>` of components, `Map<String,String>` and `Map<Enum,T>`, plus a `file` attribute (per the open assumption above) and a MiniMessage `Component` binding to replace `plugin-infrastructure`'s `ComponentSerializer`.
  *Files:* `ConfigurationProcessor.java`, `ConfigurationLoader.java`, `annotations/ConfigurationComponent.java`, tests.

- [ ] **T0.4 — Parameterised `@Action` targets in `usher`.**
  *Why:* `SearchDialog` builds one button per config-defined region tag, each closing over its own `tagId` and cycling Ignore→Include→Exclude. Usher's `ButtonSpec.action(label, "name")` targets a **statically named** `@Action` method resolved from a `Map<String,Method>` — there is no per-button payload, and the tag set is runtime config, so one method per tag is impossible.
  *Shape:* `ButtonSpec.action(label, "cycleTag", "residential")` routed to `@Action("cycleTag") void cycle(@ActionArg String tagId, @Model SearchModel m, DialogFlow flow)`.
  *Files:* `ButtonSpec.java`, `DialogManager.java` (`dispatchAction`/`injectParam`), new `annotations/ActionArg.java`, tests.
  *Not needed:* dynamic **inputs** already work — `DialogContext` is injectable and exposes the raw `DialogResponseView`, so `SubregionDialog`'s `tag_0…tag_n` boolean inputs read fine unchanged.

---

## Phase 1 — Build, bootstrap, DI

- [ ] **T1.1** Add `implementation("io.paradaux:hibernia-framework:1.1.0-realty-SNAPSHOT")` to `realty-paper`. Repos already present in `realty-conventions`.
- [ ] **T1.2** shadowJar relocations for `com.google.inject`, `com.google.common`, `org.reflections`, `javax.inject`, `org.aopalliance`, `javassist`. **Verify against the adapter contract** — `chat-adapter` and `essentials-adapter` must still load; add a `runServer` smoke check to the task's done-criteria.
- [ ] **T1.3** New `RealtyModule extends AbstractModule` binding the services `Realty.java` currently constructs by hand: `Database`, `RealtyBackend`, `RealtyPaperApi`, `ExecutorState`, `PartyService`, `EconomyProvider`, `RegionProfileService`, `SignCache`, `SignTextApplicator`, `ProfileApplicator`, `RealtyEventDispatch`, `SquirrelIdUsernameResolver`, `SafeLocationFinder`, `ModuleLifecycleManager`.
- [ ] **T1.4** Economy resolution (`Treasury` → `Vault` → self-disable) becomes a Guice `@Provides` method; the late-bound `PartyService`↔`RealtyBackend` cycle keeps its supplier indirection.
- [ ] **T1.5** Rewrite `Realty.onEnable`/`onDisable` around `HiberniaModule` + injector. Preserve exactly: schema migration before anything touches the DB, executor shutdown order, modules started last and stopped first.

## Phase 2 — Configuration

- [ ] **T2.1** `DatabaseSettings`, `Settings`, `TaxSettings` → `@ConfigurationComponent` (flat; needs no T0.3 features).
- [ ] **T2.2** `RegionProfileSettings` / `GroupedRegionProfile` / `RegionProfile` → nested components (needs T0.3).
- [ ] **T2.3** `RegionTagSettings` / `ConfigRegionTag` / `TagPermission` → list-of-components; keep runtime Bukkit permission registration and the orphaned-tag warning.
- [ ] **T2.4** `/realty reload` → `ConfigurationLoader.reload()`, then re-register tag permissions and re-run `ProfileApplicator.applyAll(...)`. Identity-preserving reload means the `AtomicReference<Settings>` indirection can go.
- [ ] **T2.5** Drop `configurate-yaml` from `realty-paper` and its shadowJar relocations **only if** nothing else needs it (`plugin-infrastructure`'s `MessageContainer` does — resolved in Phase 3).

## Phase 3 — Messages

523 `messageFor(...)` call sites, 429 `MessageKeys` constants, 62 distinct placeholder names.

- [ ] **T3.1** Build-time generator: flatten `messages.yml` → `messages.properties` (`a.b.c=`), rewriting `<name>` → `{name}` **only** for the 62 known placeholder names (`region`, `player`, `price`, `prefix`, …). MiniMessage tags (`<red>`, `<gradient:…>`, `<b>`, `<click:…>`) must pass through untouched — this is the one step where a naive regex silently corrupts 820 lines of formatting.
- [ ] **T3.2** Runtime one-shot converter in `onLoad`: if `messages.yml` exists and `messages.properties` does not, run the same transform over the **operator's** file so their edits survive, then rename to `messages.yml.migrated`.
- [ ] **T3.3** Rewrite the 523 call sites: `messages.messageFor(KEY, Placeholder.unparsed("region", id))` → `message.component(KEY, "region", id)`. Keep `MessageKeys` as constants, now holding property keys.
- [ ] **T3.4** Pagination click-links (`MessageContainer.deserializeRaw`) → `Message.rich(...)`, which is the framework's sanctioned trusted-markup escape hatch. Delete `MessageContainer`.
- [ ] **T3.5** Wire `hibernia.error.*` keys so semantic exceptions render in Realty's voice with the `{prefix}`.
- [ ] **T3.6** Test: every `MessageKeys` constant resolves; every `{placeholder}` in the bundle is supplied by at least one call site.

## Phase 4 — Commands

- [ ] **T4.1** Custom `ParameterResolver`s: `WorldGuardRegion` (omitted `[region]` arrives as `null` → existing stand-in-the-region fallback is preserved **verbatim**), `NamedAuthority`/party, `Duration`, `HistoryEventType`, `OccupancyFilter`, module name, tag id, help category.
- [ ] **T4.2–T4.9** Port ~30 `CustomCommandBean`s to `@Command({"realty","rl"})` handler classes, one per current group. **Verified safe:** `CommandManager` merges multiple handler classes under one root label with startup conflict detection, so the flat `/realty` tree survives intact.
- [ ] **T4.10** Flags via `@Flag` (T0.2).
- [ ] **T4.11** `@Permission` per route, node strings unchanged; `paper-plugin.yml` untouched.
- [ ] **T4.12** `HelpCommand` → `CommandManager.routeIndex()` + `@Description`, reproducing today's categories and pagination.
- [ ] **T4.13** Replace `BrigadierArgumentSyntaxTest` with a route-registration validation test. Delete Cloud dep + relocations.

## Phase 5 — Listeners

- [ ] **T5.1** `SignInteractionListener`, `SubregionWandListener`, `RegionNotificationListener` → `.listeners(...)` + `ListenerManager`.
- [ ] **T5.2** `PropertyTaxListener` stays conditionally registered (Treasury-only) — `ListenerManager` registers unconditionally, so this one keeps manual registration behind the existing `isPluginEnabled("Treasury")` guard.

## Phase 6 — Dialogs (Usher)

- [ ] **T6.1** `SearchDialogHandler`: `@Screen` main (bool/text/option inputs — all covered by Usher's `TEXT/BOOLEAN/TOGGLE/NUMBER/OPTION` kinds), `@Screen` tags (dynamic grid via T0.4, dynamic `columns(...)` already supported), `@Screen` results. `SearchState` map → `@Model`; manual re-show → `DialogFlow.refresh()/back()`; DB search → `flow.await(...)`.
- [ ] **T6.2** `SubregionDialogHandler`: height / create / tags / confirm screens. Dynamic `tag_N` inputs read via injected `DialogContext`.
- [ ] **T6.3** `InputBinder`s for `DurationUnit` and `OccupancyFilter` if the built-in enum binding isn't sufficient.
- [ ] **T6.4** Bedrock support is available for free via `.bedrockSupport(...)` — note as a follow-up, not in scope.

## Phase 7 — Adapters, extension, importer

- [ ] **T7.1** Confirm both adapters load and deliver under relocation (chat + Essentials mail smoke test via `runServer`).
- [ ] **T7.2** `realty-paper-plan-extension` and `realty-areashop-importer`: verify only; neither touches the migrated tiers.

## Phase 8 — Verification

- [ ] **T8.1** All existing tests green; update only those coupled to Cloud/Configurate/MessageContainer.
- [ ] **T8.2** `runServer` manual matrix: every command family, both dialogs, sign interaction, subregion wand, auction expiry, tax cycle, `/realty reload`, module reload.
- [ ] **T8.3** Fresh-install and upgrade-from-1.4.9 runs — the upgrade path is the one operators actually take.

## Phase 9 — Docs

- [ ] **T9.1** README module table + build instructions.
- [ ] **T9.2** Operator upgrade note: what converts automatically (messages), what doesn't, how to roll back.

---

## Risk register

| Risk | Mitigation |
|---|---|
| **Flag tab-completion regression** — the one feature Hibernia cannot express | T0.2 before Phase 4; no command ships until its flags complete as they do today |
| **Message converter corrupting MiniMessage** | Convert only the 62 known placeholder names; golden-file test over all 820 lines |
| **Guice relocation breaking adapter module loading** | T1.2 gated on a live `runServer` load of both adapters |
| **Silent permission-default change** (an undeclared node defaults to OP) | `PermissionManifestTest` stays green throughout; node strings never edited |
| **Usher dynamic tag grid** | T0.4 designed and tested on the fork before Phase 6 starts |
| **Fork divergence from upstream** | Feature branch kept rebasable; repin to an official release if/when Paradaux ships equivalents |

## Sequencing

Phase 0 gates Phases 2, 4 and 6. Phases 1, 3 and 5 can proceed against stock 1.1.0. Single branch, one commit per task.
