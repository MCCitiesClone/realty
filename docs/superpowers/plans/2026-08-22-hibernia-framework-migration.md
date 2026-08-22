# Hibernia Framework Migration Plan

**Goal:** Migrate Realty from its current stack (Incendo Cloud commands, Configurate configuration, `plugin-infrastructure` MessageContainer, hand-rolled Paper Dialog API screens, 937 lines of manual constructor wiring in `Realty.java`) onto `io.paradaux:hibernia-framework`, **preserving every existing feature and every operator-facing behaviour**.

**Branch:** `feat/hibernia-framework-migration` (created off `main` @ `365986e`).

**Framework version:** the fork's `feat/realty-requirements`, branched from upstream **`develop` (1.2.0-unreleased)** — not the 1.1.0 release. `develop` is materially ahead and several of its additions land directly on this plan (see *Basing on develop*). Java 21, Paper 1.21.x.

**Fork:** [`MCCitiesClone/hibernia-framework`](https://github.com/MCCitiesClone/hibernia-framework), branch `feat/realty-requirements`. Consumed by Realty through JitPack (`com.github.MCCitiesClone:hibernia-framework`), whose repository `realty-conventions` already declares. **No PRs to ParadauxIO without explicit per-PR permission.**

---

## Scope decisions (from Evan, 2026-08-22)

| Axis | Decision |
|---|---|
| Cloud flags | Add `@Flag` support to Hibernia via a **fork**, preserving syntax *and* tab-completion |
| Configuration | **All** config moves to Hibernia's configurator (requires extending it) |
| Messages | Migrate to `messages.properties` **plus** a one-shot converter that preserves operator edits |
| Dialogs | Migrate **both** `SearchDialog` and `SubregionDialog` to Usher |
| Fork | `MCCitiesClone/hibernia-framework`. **No upstream PRs to ParadauxIO without explicit per-PR permission.** |

### Config layout — decided

Confirmed: extend the configurator with per-file components. All five operator-facing files (`settings.yml`, `database.yml`, `profiles.yml`, `region-tags.yml`, `taxes.yml`) keep their current names, contents and comments, so the upgrade is a no-op for every existing server. Implemented in T0.3.

### Framework work added during implementation

Beyond the three planned prerequisites, the fork needed four more changes, each found by the migration rather than predicted:

| Change | Why it was needed |
|---|---|
| **Record binding** (`7d68f66`) | Every Realty settings type is an immutable record with a compact constructor that normalises defaults. Field injection cannot touch a record, and rewriting them as mutable POJOs would have destroyed that normalisation. Records are now *constructed*, so those constructors still run. |
| **`UUID` and `Set<T>`** (`7d68f66`) | Ordinary config shapes — authority account ids, grouped region name sets — that were simply unreachable. |
| **Reconcile component-named files** (`3df3cb3`) | The old loader merged newly shipped default keys into operator files on every start. Hibernia's reconciler covered only `config.yml`/`messages.properties`, so per-file components would have silently stopped receiving new defaults on upgrade. |
| **Tolerate no packaged `config.yml`** (`f15098b`) | Bukkit's `saveDefaultConfig()` throws when the jar ships none — which is exactly Realty's layout now that every component names its own file. |

### Basing on develop — what it changes

Upstream `develop` carries an unreleased 1.2.0 with several things this plan had budgeted for:

| Upstream already has | Effect on this plan |
|---|---|
| **`HelpGenerator`** — paginated, permission-filtered help built from `routeIndex()` | T4.12 shrinks from "reimplement" to "adopt and match current output" |
| **Defaults reconciliation on upgrade** — additively merges new jar keys into operator files, comment-preserving for YAML, line-based for `.properties` | Directly serves the messages/config upgrade story in T3.2 |
| **`KeyedException`** — semantic exceptions take a message key plus placeholders | T3.5 becomes wiring rather than design |
| **Supertype/interface parameter resolvers** | One `WorldGuardRegion` resolver can service subtypes |
| **`BigDecimal` config fields** | Prices and tax amounts avoid `double` |

⚠ **Atomic config reload changes T2.4.** `ConfigurationLoader.reload()` now swaps in fresh component instances rather than mutating them, so a Guice-injected config singleton captured at startup keeps showing its original values. Realty must read config through `ConfigurationLoader.getComponent(...)` at point of use. The existing `AtomicReference<Settings>` indirection therefore **does not disappear** — it is replaced by loader lookups, which is the same shape. (An earlier draft of this plan said the indirection could go; that was wrong.)

⚠ **Paper API target.** `develop` bumped to `paper-api:1.21.11`; Realty compiles against `1.21.8`. To confirm before Phase 1 that the framework uses no API newer than 1.21.8, or bump Realty.

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

- [x] **T0.1 — Fork and baseline.** Forked to `MCCitiesClone/hibernia-framework`, branch `feat/realty-requirements` off `upstream/develop`. Baseline verified: builds on Java 21 with tests and the 95%/83% JaCoCo gate green. No PR to ParadauxIO.

- [x] **T0.2 — `@Flag` support in `commander`.** *(done — `7f33db7`)*
  *Why:* Realty uses ~20 flags across six commands (`list`, `history`, `search`, `terminate`, `create`, `register`) — `--page`, `--player`, `--event`, `--time`, `--now`, `--price`, `--titleholder`, `--authority`, `--landlord`, `--tags`, `--exclude-tags`, `--min-price`, `--max-price`, `--occupancy`, `--freehold`, `--leasehold`. `CommandManager` has **zero** flag handling; routes are literals, `<req>`, `[opt]` and one terminal greedy string.
  *Shape:* `@Flag("page") Integer page` / `@Flag(value = "now", presence = true) boolean now`, flags parsed as a trailing repeatable `--name value` node set in the Brigadier tree, resolved through the existing `ParameterResolver` registry (so `--player` tab-completes exactly as it does today), validated at registration alongside the existing route conflict checks.
  *Built:* `@Flag(value, aliases, defaultValue, presence, sanitize)`. Flags reach Brigadier as **one trailing greedy node**, not a chain of literal/argument nodes — chaining needs each node to redirect back to a dispatch node so flags can come in any order, and a Brigadier redirect opens a fresh `CommandContext`, leaving the route's own positional arguments unreachable from the context that executes. `FlagTail` supplies the grammar Brigadier would have provided, serving execution and completion from one token stream. Accepts `--n v`, `--n=v`, quoted values, any order; attaches at every executable path so optional-segment truncations take flags too. Rejected at registration: presence flags on non-booleans, primitive value flags without a default, duplicate names across aliases, flags shadowing an argument, `@GreedyArg` plus flags. 52 tests.

- [x] **T0.3 — Nested configuration support in `configurator`.** *(done — `dfe7287`)*
  *Why:* `profiles.yml` is a map of region-state → `{priority, flags: Map<String,String>, sign: {lines: List, right-click-commands: List, left-click-commands: List}}` plus a `grouped` list of objects; `region-tags.yml` is a list of objects with nested permission blocks; `taxes.yml` has a `rules` list of objects. `ConfigurationProcessor` supports only `String`, numerics, `boolean`, `List<String>` and enums.
  *Shape:* nested `@ConfigurationComponent` types, `List<T>` of components, `Map<String,String>` and `Map<Enum,T>`, plus a `file` attribute (per the open assumption above) and a MiniMessage `Component` binding to replace `plugin-infrastructure`'s `ComponentSerializer`.
  *Built:* binding is now recursive and section-relative. New `@ConfigurationObject` for nested POJOs; field types gain `Map<K,V>` (String/enum/Integer keys), `List<T>` of objects, nested objects and MiniMessage `Component`. `@ConfigurationComponent(file=, path=)` for per-file and sub-rooted components; `getFile(name)` for direct access. 20 tests, run against real parsed YAML.
  *Two findings, both bearing on Realty:* Bukkit's `set()` stores a nested map as a raw `Map`, so every object inside a list entry bound to `null` until the wrapper switched to `createSection(path, map)`. And enum matching had to become case-insensitive — YAML reads unquoted `TRUE` as a **boolean**, rendered back as `"true"`, so `region-tags.yml`'s `default: TRUE` would otherwise fail to bind against a constant named `TRUE`.

- [x] **T0.4 — Parameterised `@Action` targets in `usher`.** *(done — `524b496`)*
  *Why:* `SearchDialog` builds one button per config-defined region tag, each closing over its own `tagId` and cycling Ignore→Include→Exclude. Usher's `ButtonSpec.action(label, "name")` targets a **statically named** `@Action` method resolved from a `Map<String,Method>` — there is no per-button payload, and the tag set is runtime config, so one method per tag is impossible.
  *Shape:* `ButtonSpec.action(label, "cycleTag", "residential")` routed to `@Action("cycleTag") void cycle(@ActionArg String tagId, @Model SearchModel m, DialogFlow flow)`.
  *Built:* an ACTION `ButtonSpec` may carry an argument, delivered via `@ActionArg` (String, numerics, boolean, enums). A null argument fails loudly for a primitive parameter rather than passing `0`, which would read as a real click on item zero. 18 tests.
  *Not needed:* dynamic **inputs** already work — `DialogContext` is injectable and exposes the raw `DialogResponseView`, so `SubregionDialog`'s `tag_0…tag_n` boolean inputs read fine unchanged.

---

## Phase 1 — Build, bootstrap, DI  *(complete)*

- [x] **T1.1** Add the JitPack dependency on the fork to `realty-paper`; the `jitpack` repository is already in `realty-conventions`. Confirm the Paper API version question above first.
- [x] **T1.2** shadowJar relocations for `com.google.inject`, `com.google.common`, `org.reflections`, `javax.inject`, `org.aopalliance`, `javassist`. **Verify against the adapter contract** — `chat-adapter` and `essentials-adapter` must still load; add a `runServer` smoke check to the task's done-criteria.
- [x] **T1.3** New `RealtyModule extends AbstractModule` binding the services `Realty.java` currently constructs by hand: `Database`, `RealtyBackend`, `RealtyPaperApi`, `ExecutorState`, `PartyService`, `EconomyProvider`, `RegionProfileService`, `SignCache`, `SignTextApplicator`, `ProfileApplicator`, `RealtyEventDispatch`, `SquirrelIdUsernameResolver`, `SafeLocationFinder`, `ModuleLifecycleManager`.
- [~] **T1.4** Economy resolution stays as `resolveEconomyProvider()` and is bound `toInstance`, not moved to `@Provides`. Its Treasury → Vault → self-disable sequence has to run *before* the injector exists, because a missing economy disables the plugin outright.
- [~] **T1.5** `HiberniaModule` is built in `onLoad` (config must be readable before the plugin decides whether it can enable) and the injector in `onEnable`, after the service graph exists. `Realty.java` has not yet shrunk to a lifecycle shell — the Cloud command registration and the Configurate message loader still live there, and go in Phases 3–4.

## Phase 2 — Configuration  *(complete)*

- [x] **T2.1** `DatabaseSettings`, `Settings`, `TaxSettings` → `@ConfigurationComponent` (flat; needs no T0.3 features).
- [x] **T2.2** `RegionProfileSettings` / `GroupedRegionProfile` / `RegionProfile` → nested components (needs T0.3).
- [x] **T2.3** `RegionTagSettings` / `ConfigRegionTag` / `TagPermission` → list-of-components; keep runtime Bukkit permission registration and the orphaned-tag warning.
- [x] **T2.4** `/realty reload` → `ConfigurationLoader.reload()`, then re-register tag permissions and re-run `ProfileApplicator.applyAll(...)`. Identity-preserving reload means the `AtomicReference<Settings>` indirection can go.
- [~] **T2.5** Configurate is gone from `realty-backend` and `realty-paper-api`. It remains in `realty-paper` for `messages.yml` alone, and goes with Phase 3.

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
- [ ] **T4.12** `HelpCommand` → upstream `HelpGenerator` + `@Description`, reproducing today's categories and pagination.
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
- [x] **T7.2** `realty-areashop-importer` removed from this branch — the branch targets fresh servers and those already migrated off AreaShop, and the module's only dependency (the archived `md5sha256/AreaShop` fork) had stopped resolving, so CI already skipped it. `realty-paper-plan-extension`: verify only; it touches none of the migrated tiers.

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
| **Fork divergence from upstream** | Branched from `develop` rather than the release, so the delta stays small and rebasable; repin to an official release if/when Paradaux ships equivalents |
| **Paper API 1.21.11 vs Realty's 1.21.8** | Audited before Phase 1; bump Realty or hold the framework at 1.21.8 |

## Sequencing

Phase 0 is **complete**. It gated Phases 2, 4 and 6, which are now unblocked. Single branch, one commit per task.
