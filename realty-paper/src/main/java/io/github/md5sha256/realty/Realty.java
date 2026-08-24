package io.github.md5sha256.realty;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLoader;
import com.minecraftcitiesnetwork.pluginInfrastructure.util.DateFormatter;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.md5sha256.realty.api.CurrencyFormatter;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.api.ProfileApplicator;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.RealtyPaperApiImpl;
import io.github.md5sha256.realty.api.RegionProfileService;
import io.github.md5sha256.realty.api.RegionState;
import io.github.md5sha256.realty.api.SignCache;
import io.github.md5sha256.realty.api.SignProfile;
import io.github.md5sha256.realty.api.SignTextApplicator;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.AuctionEndedEvent;
import io.github.md5sha256.realty.api.event.LeaseExpiredEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminatedEvent;
import io.github.md5sha256.realty.api.event.RealtyNotificationEvent;
import io.github.md5sha256.realty.command.util.SafeLocationFinder;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.database.RealtyBackendImpl;
import io.github.md5sha256.realty.database.SqlSessionWrapper;
import io.github.md5sha256.realty.database.maria.MariaDatabase;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.github.md5sha256.realty.listener.PropertyTaxListener;
import io.github.md5sha256.realty.listener.RegionNotificationListener;
import io.github.md5sha256.realty.wand.SubregionWand;
import io.github.md5sha256.realty.wand.SubregionWandManager;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.localisation.MessageKeys;
import io.github.md5sha256.realty.localisation.MessagesYamlConverter;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.GroupedRegionProfile;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.settings.RegionProfile;
import io.github.md5sha256.realty.settings.RegionProfileSettings;
import io.github.md5sha256.realty.settings.RegionTagSettings;
import io.github.md5sha256.realty.settings.Settings;
import io.github.md5sha256.realty.settings.TaxSettings;
import io.github.md5sha256.realty.util.SquirrelIdUsernameResolver;
import io.github.md5sha256.realty.command.RealtyCommands;
import io.github.md5sha256.realty.dialog.SearchDialogHandler;
import io.github.md5sha256.realty.dialog.SubregionDialogHandler;
import io.github.md5sha256.realty.listener.RealtyListeners;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.configurator.ConfigurationLoader;
import io.paradaux.hibernia.framework.events.ListenerManager;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.papermc.paper.util.Tick;
import io.github.md5sha256.realty.economy.EconomyProvider;
import io.github.md5sha256.realty.economy.GovernmentAccountLookup;
import io.github.md5sha256.realty.party.PartyDomains;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.economy.TreasuryEconomyProvider;
import io.github.md5sha256.realty.economy.VaultEconomyProvider;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.World;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class Realty extends JavaPlugin {

    /**
     * The operator-facing YAML files backing the configuration components. Listed so the
     * framework merges newly shipped default keys into them on upgrade, which is what the
     * old hand-rolled loader did on every start.
     */
    private static final String[] CONFIG_FILES = {
            "settings.yml", "database.yml", "profiles.yml", "region-tags.yml", "taxes.yml"
    };

    private Message messageContainer;
    private final AtomicReference<Settings> settings = new AtomicReference<>();
    private final AtomicReference<RegionProfileSettings> regionFlagSettings = new AtomicReference<>();
    private final AtomicReference<RealtyTags> realtyTags = new AtomicReference<>();
    private final AtomicReference<TaxSettings> taxSettings = new AtomicReference<>();
    private final RegionProfileService regionProfileService = new RegionProfileService(getLogger());
    private final SignCache signCache = new SignCache();
    private EconomyProvider economyProvider;
    private PartyService partyService;
    private SquirrelIdUsernameResolver nameResolver;
    private ExecutorState executorState;
    private RealtyBackend logic;
    private ProfileApplicator profileApplicator;
    private DatabaseSettings databaseSettings;
    private Database database;
    private SignTextApplicator signTextApplicator;
    private RealtyPaperApi paperApi;
    private RealtyEventDispatch eventDispatch;
    private ModuleLifecycleManager<Realty> moduleManager;
    private SubregionWand subregionWand;
    private SubregionWandManager subregionWandManager;
    private HiberniaModule hibernia;
    private ConfigurationLoader configuration;
    private Injector injector;
    private boolean failedLoad = false;

    private static @NotNull PermissionDefault toBukkitPermission(@NotNull ConfigRegionTag tag) {
        if (tag.permission() == null) {
            throw new IllegalArgumentException("tag has a null permission");
        }
        return switch (tag.permission().permissionDefault()) {
            case OP -> PermissionDefault.OP;
            case TRUE -> PermissionDefault.TRUE;
            case FALSE -> PermissionDefault.FALSE;
        };
    }

    @NotNull
    public Database database() {
        return Objects.requireNonNull(this.database, "Database not initialized!");
    }

    public RealtyBackend logic() {
        return this.logic;
    }

    public Settings settings() {
        return this.settings.get();
    }

    public ExecutorState executorState() {
        return this.executorState;
    }

    public RealtyPaperApi paperApi() {
        return this.paperApi;
    }

    /**
     * Realty's Guice injector. Available from the end of {@link #onEnable()}.
     *
     * <p>Deliberately not exposed to module jars: the adapters exchange only Realty's own
     * types and pre-rendered components with core, and the DI stack is relocated in the shaded
     * jar, so an adapter compiled against these types would not resolve them at runtime.</p>
     */
    @NotNull
    Injector injector() {
        return Objects.requireNonNull(this.injector, "Injector not initialized!");
    }

    public RegionProfileSettings regionFlagSettings() {
        return this.regionFlagSettings.get();
    }

    public RealtyTags realtyTags() {
        return this.realtyTags.get();
    }

    public TaxSettings taxSettings() {
        return this.taxSettings.get();
    }

    @Override
    public void onLoad() {
        try {
            initDataFolder();
            copyResourceTemplate("messages.properties", "defaults/default-messages.properties");
            copyResourceTemplate("settings.yml", "defaults/default-settings.yml");
            copyResourceTemplate("profiles.yml", "defaults/default-profiles.yml");
            copyResourceTemplate("taxes.yml", "defaults/default-taxes.yml");
            // Carry an operator's customised messages.yml across before the framework's message
            // bean runs; it writes a stock messages.properties when it finds none, which would
            // silently discard their edits.
            MessagesYamlConverter.migrateIfNeeded(getDataFolder().toPath(), getLogger());
            // Built during onLoad, not onEnable: the database settings decide whether the plugin
            // may enable at all, and the profile and tag settings are needed before the first
            // region is touched. The injector itself comes later, once the services exist.
            this.hibernia = HiberniaModule.forPlugin(this)
                    .scanConfiguration("io.github.md5sha256.realty.settings")
                    .scanConfiguration("io.github.md5sha256.realty")
                    .reconcileFiles(CONFIG_FILES)
                    .handlers(RealtyCommands.handlers())
                    .resolvers(RealtyCommands.resolvers())
                    .listeners(RealtyListeners.listeners())
                    .dialogs(SearchDialogHandler.class, SubregionDialogHandler.class)
                    .build();
            this.databaseSettings = this.hibernia.configuration(DatabaseSettings.class);
            this.settings.set(this.hibernia.configuration(Settings.class));
            this.regionFlagSettings.set(this.hibernia.configuration(RegionProfileSettings.class));
            this.realtyTags.set(new RealtyTags(this.hibernia.configuration(RegionTagSettings.class)));
            this.taxSettings.set(this.hibernia.configuration(TaxSettings.class));
            registerTagPermissions(this.realtyTags.get());
            configureRegionFlagService(this.regionFlagSettings.get());

            if (this.databaseSettings.url().isEmpty()) {
                getLogger().severe("Database url is empty!");
                getServer().getPluginManager().disablePlugin(this);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            failedLoad = true;
        }
    }

    @Override
    public void onEnable() {
        if (failedLoad) {
            getLogger().severe("Failed to initialize plugin, check earlier logs");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Plugin startup logic
        ClassLoader pluginClassLoader = getClass().getClassLoader();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setContextClassLoader(pluginClassLoader);
            return thread;
        };
        this.executorState = new ExecutorState(getServer().getScheduler()
                .getMainThreadExecutor(this),
                Executors.newFixedThreadPool(4, threadFactory),
                Executors.newThreadPerTaskExecutor(threadFactory));
        try {
            this.nameResolver = new SquirrelIdUsernameResolver(
                    new File(getDataFolder(), "profiles.sqlite"),
                    this.executorState.networkExec());
        } catch (IOException ex) {
            getLogger().severe("Failed to initialize profile cache!");
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MariaDatabase mariaDatabase = new MariaDatabase(this.databaseSettings, getLogger());
        this.database = mariaDatabase;
        try {
            mariaDatabase.initializeSchema(Path.of("sql/migrations"));
        } catch (IOException | SQLException ex) {
            getLogger().severe("Schema migration failed!");
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Resolved before the backend so both it and the economy providers can be given the same
        // view of which party UUIDs stand for a government. The service reads the backend through
        // a supplier because the backend takes it as its PartyAuthorizer — one of the two has to
        // be late-bound.
        this.partyService = new PartyService(() -> this.logic, resolveTreasuryApi(),
                this.nameResolver::getUsername, getLogger());
        this.logic = new RealtyBackendImpl(mariaDatabase,
                this.partyService::displayName,
                dateTime -> DateFormatter.format(this.settings.get().dateFormat(), dateTime),
                () -> this.settings.get().offerPaymentDurationSeconds(),
                this.partyService);
        this.partyService.refresh();
        EconomyProvider economyProvider = resolveEconomyProvider();
        this.economyProvider = economyProvider;
        if (economyProvider == null) {
            getLogger().severe("No economy found (neither Treasury nor Vault), plugin will now disable!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SafeLocationFinder safeLocationFinder = new SafeLocationFinder();
        this.signTextApplicator = new SignTextApplicator(
                this.regionProfileService, this.logic, this.database, this.signCache, getLogger());
        this.profileApplicator = new ProfileApplicator(
                this, this.regionProfileService, this.executorState, this.logic,
                this.signTextApplicator, this.signCache);
        this.profileApplicator.applyAll(this.settings.get().profileReapplyPerTick());
        this.paperApi = new RealtyPaperApiImpl(
                this.logic, economyProvider, this.executorState, this.database,
                this.regionProfileService, this.signTextApplicator, this.signCache,
                () -> this.settings.get().terminationNoticeSeconds(), safeLocationFinder,
                this.partyService);
        this.eventDispatch = new RealtyEventDispatch(
                getServer(),
                this.executorState.mainThreadExec(),
                task -> getServer().getScheduler().runTaskAsynchronously(this, task));
        this.moduleManager = new ModuleLifecycleManager<>(this,
                new ModuleLoader(getDataFolder().toPath().resolve("modules")),
                Realty.class.getName(),
                getLogger());
        // Built here rather than inside registerCommands so the injector can bind them: the
        // subregion flow reaches the wand from both a command and a listener.
        this.subregionWand = new SubregionWand(this, this.settings);
        this.subregionWandManager = new SubregionWandManager();
        this.injector = createInjector(economyProvider, safeLocationFinder);
        scheduleTasks();
        this.injector.getInstance(CommandManager.class).registerAll();
        this.injector.getInstance(ListenerManager.class).registerAll();
        registerConditionalListeners();
        getServer().getServicesManager()
                .register(RealtyBackend.class, this.logic, this, ServicePriority.Normal);
        getServer().getServicesManager()
                .register(RealtyPaperApi.class, this.paperApi, this, ServicePriority.Normal);
        warnOrphanedTags();
        // Modules start last so that everything they might reach for — the API services, commands
        // and listeners — is already in place.
        startModules();
        getLogger().info("Plugin enabled successfully");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (this.moduleManager != null) {
            // Shut modules down first: they may still be using the executors and database below.
            this.moduleManager.stop();
        }
        if (this.profileApplicator != null) {
            this.profileApplicator.cancel();
        }
        if (this.executorState != null) {
            try (ExecutorService dbService = this.executorState.dbExec();
                 ExecutorService networkService = this.executorState.networkExec()) {
                dbService.shutdownNow();
                networkService.shutdownNow();
                if (!dbService.awaitTermination(30, TimeUnit.SECONDS)) {
                    getLogger().severe("Failed to await database threadpool shutdown!");
                }
                if (!networkService.awaitTermination(30, TimeUnit.SECONDS)) {
                    getLogger().severe("Failed to await network threadpool shutdown!");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                ex.printStackTrace();
            }
        }
        if (this.database != null) {
            try {
                this.database.close();
            } catch (IOException ex) {
                getLogger().severe("Failed to close database connection pool: " + ex.getMessage());
            }
        }
        getLogger().info("Plugin disabled successfully");
    }

    /**
     * Builds the Guice injector over the service graph {@link #onEnable()} has just finished
     * constructing.
     *
     * <p>The Hibernia module itself was built in {@link #onLoad()}, because configuration has to
     * be readable before the plugin decides whether it can enable.</p>
     */
    private @NotNull Injector createInjector(@NotNull EconomyProvider economyProvider,
                                             @NotNull SafeLocationFinder safeLocationFinder) {
        Injector created = Guice.createInjector(this.hibernia, new RealtyModule(
                this,
                this.settings,
                this.regionFlagSettings,
                this.realtyTags,
                this.taxSettings,
                this.executorState,
                this.database,
                this.logic,
                this.paperApi,
                economyProvider,
                this.partyService,
                this.regionProfileService,
                this.signCache,
                this.signTextApplicator,
                this.profileApplicator,
                this.eventDispatch,
                this.nameResolver,
                safeLocationFinder,
                this.subregionWand,
                this.subregionWandManager,
                this.moduleManager));
        this.configuration = created.getInstance(ConfigurationLoader.class);
        this.messageContainer = created.getInstance(Message.class);
        return created;
    }

    /**
     * Listeners the framework cannot register for us.
     *
     * <p>{@code ListenerManager} registers its set unconditionally; the property-tax listener only
     * makes sense when Treasury is present, so it stays behind the same check it always had.</p>
     */
    private void registerConditionalListeners() {
        if (getServer().getPluginManager().isPluginEnabled("Treasury")) {
            registerTreasuryTaxProvider();
        }
    }

    private void registerTreasuryTaxProvider() {
        var treasuryRegistration = getServer().getServicesManager()
                .getRegistration(io.paradaux.treasury.api.TreasuryApi.class);
        if (treasuryRegistration != null) {
            getServer().getPluginManager().registerEvents(
                    new PropertyTaxListener(this.database, treasuryRegistration.getProvider(),
                            this.taxSettings, getLogger()), this);
            getLogger().info("Registered property tax listener (daily cycle)");
        }
    }

    /**
     * Returns Treasury's API, or {@code null} when Treasury is absent. Governments are a Treasury
     * concept: without it they can neither be registered nor authorized nor paid.
     */
    private @Nullable io.paradaux.treasury.api.TreasuryApi resolveTreasuryApi() {
        if (!getServer().getPluginManager().isPluginEnabled("Treasury")) {
            return null;
        }
        var registration = getServer().getServicesManager()
                .getRegistration(io.paradaux.treasury.api.TreasuryApi.class);
        return registration != null ? registration.getProvider() : null;
    }

    private @Nullable EconomyProvider resolveEconomyProvider() {
        GovernmentAccountLookup governmentAccounts = this.partyService::accountId;
        io.paradaux.treasury.api.TreasuryApi treasuryApi = resolveTreasuryApi();
        if (treasuryApi != null) {
            getLogger().info("Detected Treasury, using Treasury as the economy provider (full ledger support)");
            return new TreasuryEconomyProvider(treasuryApi, governmentAccounts);
        }
        if (getServer().getPluginManager().isPluginEnabled("Treasury")) {
            getLogger().warning("Treasury plugin is loaded but TreasuryApi service is not registered; falling back to Vault");
        }
        var registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration != null) {
            getLogger().info("Using Vault as the economy provider");
            return new VaultEconomyProvider(registration.getProvider(), governmentAccounts);
        }
        return null;
    }

    private void scheduleTasks() {
        BukkitScheduler scheduler = getServer().getScheduler();
        long intervalTicks = Tick.tick().fromDuration(Duration.ofMinutes(1));
        scheduler.runTaskTimerAsynchronously(this, () -> {
            if (this.logic == null) {
                return;
            }
            List<RealtyBackend.ExpiredBiddingAuction> endedAuctions = this.logic.clearExpiredBiddingAuctions();
            if (!endedAuctions.isEmpty()) {
                // Resolve WorldGuard regions and fire notifications/post-events on the main thread.
                scheduler.runTask(this, () -> {
                    for (RealtyBackend.ExpiredBiddingAuction auction : endedAuctions) {
                        WorldGuardRegion wgRegion = resolveRegion(auction.worldId(), auction.worldGuardRegionId());
                        if (auction.winnerId() != null) {
                            this.eventDispatch.fireSync(new RealtyNotificationEvent(
                                    List.of(auction.winnerId()),
                                    this.messageContainer.component(MessageKeys.NOTIFICATION_AUCTION_WON,
                                            "region", auction.worldGuardRegionId()),
                                    wgRegion));
                        } else {
                            this.eventDispatch.fireSync(new RealtyNotificationEvent(
                                    List.of(auction.auctioneerId()),
                                    this.messageContainer.component(MessageKeys.NOTIFICATION_AUCTION_ENDED_NO_BIDS,
                                            "region", auction.worldGuardRegionId()),
                                    wgRegion));
                        }
                        if (wgRegion != null) {
                            this.eventDispatch.fireSync(new AuctionEndedEvent(
                                    wgRegion, auction.winnerId(), auction.auctioneerId()));
                        }
                    }
                });
            }
            List<RealtyBackend.ExpiredBidPayment> expiredBidPayments = this.logic.clearExpiredBidPayments();
            if (!expiredBidPayments.isEmpty()) {
                scheduler.runTask(this, () -> {
                    for (RealtyBackend.ExpiredBidPayment payment : expiredBidPayments) {
                        WorldGuardRegion wgRegion = resolveRegion(payment.worldId(), payment.regionId());
                        this.eventDispatch.fireSync(new RealtyNotificationEvent(
                                List.of(payment.bidderId()),
                                this.messageContainer.component(MessageKeys.NOTIFICATION_BID_PAYMENT_EXPIRED,
                                        "region", payment.regionId(),
                                        "amount", CurrencyFormatter.format(payment.refundAmount())),
                                wgRegion));
                    }
                });
            }
            List<RealtyBackend.ExpiredOfferPayment> expiredOfferPayments = this.logic.clearExpiredOfferPayments();
            if (!expiredOfferPayments.isEmpty()) {
                scheduler.runTask(this, () -> {
                    for (RealtyBackend.ExpiredOfferPayment payment : expiredOfferPayments) {
                        WorldGuardRegion wgRegion = resolveRegion(payment.worldId(), payment.regionId());
                        this.eventDispatch.fireSync(new RealtyNotificationEvent(
                                List.of(payment.offererId()),
                                this.messageContainer.component(MessageKeys.NOTIFICATION_OFFER_PAYMENT_EXPIRED,
                                        "region", payment.regionId(),
                                        "amount", CurrencyFormatter.format(payment.refundAmount())),
                                wgRegion));
                    }
                });
            }
            List<RealtyBackend.ExpiredLeasehold> expiredLeaseholds = this.logic.clearExpiredLeaseholds();
            if (!expiredLeaseholds.isEmpty()) {
                Map<String, Map<String, String>> leaseholdPlaceholders = new HashMap<>();
                for (RealtyBackend.ExpiredLeasehold expired : expiredLeaseholds) {
                    leaseholdPlaceholders.put(expired.worldGuardRegionId(),
                            this.logic.getRegionPlaceholders(expired.worldGuardRegionId(),
                                    expired.worldId()));
                }
                scheduler.runTask(this, () -> {
                    for (RealtyBackend.ExpiredLeasehold expired : expiredLeaseholds) {
                        World world = getServer().getWorld(expired.worldId());
                        if (world != null) {
                            RegionManager regionManager = WorldGuard.getInstance()
                                    .getPlatform()
                                    .getRegionContainer()
                                    .get(BukkitAdapter.adapt(world));
                            if (regionManager != null) {
                                ProtectedRegion protectedRegion = regionManager.getRegion(expired.worldGuardRegionId());
                                if (protectedRegion != null) {
                                    PartyDomains.removeOwners(protectedRegion,
                                            this.partyService, expired.tenantId());
                                    WorldGuardRegion wgRegion = new WorldGuardRegion(protectedRegion, world);
                                    regionProfileService.applyFlags(
                                            wgRegion,
                                            RegionState.FOR_LEASE,
                                            leaseholdPlaceholders.getOrDefault(expired.worldGuardRegionId(),
                                                    Map.of()));
                                    // Post-event; RegionNotificationListener notifies tenant + landlord.
                                    this.eventDispatch.fireSync(new LeaseExpiredEvent(
                                            wgRegion, expired.tenantId(), expired.landlordId()));
                                }
                            }
                        }
                    }
                });
            }
            // Leaseholds whose scheduled termination date has elapsed: end them, refund any
            // prepaid-but-unused time (landlord → tenant), and notify both parties.
            List<RealtyBackend.TerminatedLeasehold> terminatedLeaseholds = this.logic.clearTerminatedLeaseholds();
            if (!terminatedLeaseholds.isEmpty()) {
                Map<String, Map<String, String>> terminatedPlaceholders = new HashMap<>();
                for (RealtyBackend.TerminatedLeasehold terminated : terminatedLeaseholds) {
                    terminatedPlaceholders.put(terminated.worldGuardRegionId(),
                            this.logic.getRegionPlaceholders(terminated.worldGuardRegionId(),
                                    terminated.worldId()));
                }
                scheduler.runTask(this, () -> {
                    for (RealtyBackend.TerminatedLeasehold terminated : terminatedLeaseholds) {
                        if (terminated.refund() > 0 && this.economyProvider != null) {
                            this.economyProvider.transfer(terminated.landlordId(), terminated.tenantId(),
                                    terminated.refund(), "Lease Termination Refund: " + terminated.worldGuardRegionId());
                        }
                        World world = getServer().getWorld(terminated.worldId());
                        if (world != null) {
                            RegionManager regionManager = WorldGuard.getInstance()
                                    .getPlatform()
                                    .getRegionContainer()
                                    .get(BukkitAdapter.adapt(world));
                            if (regionManager != null) {
                                ProtectedRegion protectedRegion = regionManager.getRegion(terminated.worldGuardRegionId());
                                if (protectedRegion != null) {
                                    PartyDomains.removeOwners(protectedRegion,
                                            this.partyService, terminated.tenantId());
                                    WorldGuardRegion wgRegion = new WorldGuardRegion(protectedRegion, world);
                                    regionProfileService.applyFlags(wgRegion, RegionState.FOR_LEASE,
                                            terminatedPlaceholders.getOrDefault(terminated.worldGuardRegionId(),
                                                    Map.of()));
                                    this.eventDispatch.fireSync(new LeaseTerminatedEvent(wgRegion,
                                            terminated.tenantId(), terminated.landlordId(),
                                            terminated.refund(), terminated.terminatedByRole()));
                                }
                            }
                        }
                    }
                });
            }
        }, intervalTicks, intervalTicks);
    }

    /**
     * Resolves a {@link WorldGuardRegion} for a sweep-produced payment record, returning
     * {@code null} when the world id is unknown or either the world or the WorldGuard region
     * itself cannot be resolved (e.g. the region row has already been deleted). Must be called
     * on the main thread.
     */
    private @Nullable WorldGuardRegion resolveRegion(@Nullable UUID worldId, @NotNull String worldGuardRegionId) {
        if (worldId == null) {
            return null;
        }
        World world = getServer().getWorld(worldId);
        if (world == null) {
            return null;
        }
        RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));
        if (regionManager == null) {
            return null;
        }
        ProtectedRegion protectedRegion = regionManager.getRegion(worldGuardRegionId);
        if (protectedRegion == null) {
            return null;
        }
        return new WorldGuardRegion(protectedRegion, world);
    }

    private void initDataFolder() throws IOException {
        File dataFolder = getDataFolder();
        if (!dataFolder.isDirectory()) {
            Files.createDirectory(dataFolder.toPath());
        }
        File defaultsFolder = new File(dataFolder, "defaults");
        if (!defaultsFolder.isDirectory()) {
            Files.createDirectory(defaultsFolder.toPath());
        }
    }






    private void unregisterTagPermissions(@NotNull RealtyTags realtyTags) {
        PluginManager pluginManager = getServer().getPluginManager();
        for (ConfigRegionTag tag : realtyTags.values()) {
            if (tag.permission() != null) {
                pluginManager.removePermission(tag.permission().node());
            }
        }
    }

    private void registerTagPermissions(@NotNull RealtyTags realtyTags) {
        PluginManager pluginManager = getServer().getPluginManager();
        for (ConfigRegionTag tag : realtyTags.values()) {
            if (tag.permission() == null) {
                continue;
            }
            PermissionDefault bukkitPermission = toBukkitPermission(tag);
            try {
                pluginManager.addPermission(new Permission(tag.permission().node(),
                        bukkitPermission));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Failed to register tag permission because it already exists: " + tag.permission()
                        .node());
            }
        }
    }

    private void warnOrphanedTags() {
        executorState.dbExec().execute(() -> {
            try (SqlSessionWrapper session = database.openSession(true)) {
                List<String> dbTagIds = session.regionTagMapper().selectDistinctTagIds();
                Set<String> configTagIds = realtyTags.get().tagIds();
                List<String> orphaned = dbTagIds.stream()
                        .filter(tagId -> !configTagIds.contains(tagId))
                        .toList();
                if (!orphaned.isEmpty()) {
                    getLogger().warning(
                            "Found orphaned tags in the database that are not in region-tags.yml: "
                                    + String.join(", ", orphaned)
                                    + ". Run /realty cleanup tags to remove them.");
                }
            } catch (Exception ex) {
                getLogger().warning("Failed to check for orphaned tags: " + ex.getMessage());
            }
        });
    }

    private void configureRegionFlagService(@NotNull RegionProfileSettings settings) {
        this.regionProfileService.clearGroupedFlagProfiles();
        this.regionProfileService.clearGroupedSignProfiles();
        Map<RegionState, RegionProfile> global = settings.global();
        if (global != null) {
            for (Map.Entry<RegionState, RegionProfile> entry : global.entrySet()) {
                this.regionProfileService.setGlobalFlagProfile(
                        entry.getKey(), entry.getValue().priority(), entry.getValue().flags());
                if (entry.getValue().sign() != null) {
                    this.regionProfileService.setGlobalSignProfile(
                            entry.getKey(), entry.getValue().sign());
                }
            }
        }
        List<GroupedRegionProfile> grouped = settings.grouped();
        if (grouped != null) {
            for (GroupedRegionProfile group : grouped) {
                Map<RegionState, RegionProfileService.FlagProfile> stateProfiles = new HashMap<>();
                Map<RegionState, SignProfile> signProfiles = new HashMap<>();
                for (Map.Entry<RegionState, RegionProfile> entry : group.states().entrySet()) {
                    stateProfiles.put(entry.getKey(),
                            new RegionProfileService.FlagProfile(
                                    entry.getValue().priority(), entry.getValue().flags()));
                    if (entry.getValue().sign() != null) {
                        signProfiles.put(entry.getKey(), entry.getValue().sign());
                    }
                }
                this.regionProfileService.addGroupedFlagProfile(group.regions(), stateProfiles);
                if (!signProfiles.isEmpty()) {
                    this.regionProfileService.addGroupedSignProfile(group.regions(), signProfiles);
                }
            }
        }
    }

    /** Re-reads every configuration file and refreshes the derived state. Driven by {@code /realty reload}. */
    public void performReload() throws IOException {
        // One re-read of every file, then a single atomic swap of the whole component set, so a
        // command running mid-reload never sees settings from one file paired with tags from
        // another. The AtomicReferences below are re-pointed at the new snapshot; consumers hold
        // the holder, not the value, so they pick the new values up without being re-injected.
        this.configuration.reload();
        this.settings.set(this.configuration.getComponent(Settings.class));
        this.regionFlagSettings.set(this.configuration.getComponent(RegionProfileSettings.class));
        unregisterTagPermissions(this.realtyTags.get());
        this.realtyTags.set(new RealtyTags(this.configuration.getComponent(RegionTagSettings.class)));
        registerTagPermissions(this.realtyTags.get());
        configureRegionFlagService(this.regionFlagSettings.get());
        this.profileApplicator.applyAll(this.settings.get().profileReapplyPerTick());
        this.taxSettings.set(this.configuration.getComponent(TaxSettings.class));
        this.partyService.refresh();
        this.messageContainer.reload();
        warnOrphanedTags();
        reloadModules();
    }

    private void startModules() {
        Path moduleDir = getDataFolder().toPath().resolve("modules");
        try {
            Files.createDirectories(moduleDir);
            try {
                BundledModuleExtractor.extract(moduleDir.resolve("chat-adapter.jar"),
                        () -> getClass().getClassLoader().getResourceAsStream("modules/chat-adapter.jar"));
            } catch (IOException ex) {
                // No chat adapter means no chat notifications, a degradation, not a fault worth
                // taking the plugin down for.
                getLogger().warning("Failed to extract bundled chat-adapter module: " + ex.getMessage());
            }
            this.moduleManager.start();
            if (this.moduleManager.getActiveModules().isEmpty()) {
                getLogger().warning("No notification delivery module is loaded. Realty fires notification "
                        + "events but delivers nothing on its own; every notification (sale, lease, offer, "
                        + "auction, etc.) will reach nobody. Place chat-adapter.jar in " + moduleDir
                        + " to enable it.");
            } else if (!this.moduleManager.getActiveModules().containsKey("chat-adapter")) {
                getLogger().warning("The chat-adapter module is not loaded. Online players will not receive "
                        + "chat notifications. Place chat-adapter.jar in " + moduleDir + " to enable it.");
            }
            if (getServer().getPluginManager().isPluginEnabled("Essentials")
                    && !this.moduleManager.getActiveModules().containsKey("essentials-adapter")) {
                getLogger().warning("Essentials is enabled, but the essentials-adapter module is not loaded. "
                        + "Offline players will not receive mail notifications and EssentialsX-based teleport "
                        + "safety will not be applied. Place essentials-adapter.jar in " + moduleDir + " to enable it.");
            }
        } catch (IOException ex) {
            // A broken module directory is not worth taking the whole plugin down for.
            getLogger().severe("Failed to load modules from " + moduleDir + ": " + ex.getMessage());
        }
    }

    /**
     * Asks every reloadable module to refresh its configuration. Called from {@code /realty reload},
     * which runs off the main thread, so the manager access is marshalled back onto it.
     */
    private void reloadModules() {
        if (this.moduleManager == null) {
            return;
        }
        this.executorState.mainThreadExec().execute(() -> {
            this.moduleManager.getActiveModules().forEach((moduleName, loadedModule) -> {
                if (!loadedModule.manifest().reloadable()) {
                    // Skip modules that declare themselves non-reloadable; reloading them would
                    // fail by design and only produce a warning operators are trained to ignore.
                    return;
                }
                this.moduleManager.reloadAsync(moduleName).exceptionally(error -> {
                    getLogger().warning("Failed to reload module " + moduleName + ": "
                            + error.getMessage());
                    return null;
                });
            });
        });
    }


    private void copyResourceTemplate(@NotNull String resourceName,
                                      @NotNull String targetName) throws IOException {
        File file = new File(getDataFolder(), targetName);
        try (InputStream inputStream = getResource(resourceName)) {
            if (inputStream == null) {
                getLogger().severe("Failed to find resource: " + resourceName);
                return;
            }
            try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                inputStream.transferTo(fileOutputStream);
            }
        }
    }


}
