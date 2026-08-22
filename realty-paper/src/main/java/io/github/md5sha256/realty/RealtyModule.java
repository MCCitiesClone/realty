package io.github.md5sha256.realty;

import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;
import io.github.md5sha256.realty.api.ExecutorState;
import io.github.md5sha256.realty.api.ProfileApplicator;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.RealtyPaperApi;
import io.github.md5sha256.realty.api.RegionProfileService;
import io.github.md5sha256.realty.api.SignCache;
import io.github.md5sha256.realty.api.SignTextApplicator;
import io.github.md5sha256.realty.command.util.SafeLocationFinder;
import io.github.md5sha256.realty.database.Database;
import io.github.md5sha256.realty.economy.EconomyProvider;
import io.github.md5sha256.realty.event.RealtyEventDispatch;
import io.paradaux.hibernia.framework.i18n.Message;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.settings.RegionProfileSettings;
import io.github.md5sha256.realty.settings.Settings;
import io.github.md5sha256.realty.settings.TaxSettings;
import io.github.md5sha256.realty.util.SquirrelIdUsernameResolver;
import io.github.md5sha256.realty.wand.SubregionWand;
import io.github.md5sha256.realty.wand.SubregionWandManager;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Binds Realty's services into Guice.
 *
 * <p>Every binding here is {@code toInstance}: the objects are still constructed by
 * {@link Realty#onEnable()} in the order their interdependencies demand — the database before the
 * backend that queries it, the party service before the economy providers that route through it,
 * the backend and party service late-bound to each other through a supplier. That ordering is
 * load-bearing and is not something a container should be left to infer, so this module publishes
 * the finished graph rather than rebuilding it.</p>
 *
 * <p>Settings are bound through their {@link AtomicReference} holders rather than as plain values,
 * because {@code /realty reload} swaps the contents. A consumer injecting a bare {@code Settings}
 * would pin the values it saw at startup and quietly ignore every later reload.</p>
 */
public final class RealtyModule extends AbstractModule {

    private final Realty plugin;
    private final Logger logger;
    private final Message messages;
    private final AtomicReference<Settings> settings;
    private final AtomicReference<RegionProfileSettings> regionProfileSettings;
    private final AtomicReference<RealtyTags> realtyTags;
    private final AtomicReference<TaxSettings> taxSettings;
    private final ExecutorState executorState;
    private final Database database;
    private final RealtyBackend backend;
    private final RealtyPaperApi paperApi;
    private final EconomyProvider economyProvider;
    private final PartyService partyService;
    private final RegionProfileService regionProfileService;
    private final SignCache signCache;
    private final SignTextApplicator signTextApplicator;
    private final ProfileApplicator profileApplicator;
    private final RealtyEventDispatch eventDispatch;
    private final SquirrelIdUsernameResolver nameResolver;
    private final SafeLocationFinder safeLocationFinder;
    private final SubregionWand subregionWand;
    private final SubregionWandManager subregionWandManager;

    RealtyModule(@NotNull Realty plugin,
                 @NotNull Message messages,
                 @NotNull AtomicReference<Settings> settings,
                 @NotNull AtomicReference<RegionProfileSettings> regionProfileSettings,
                 @NotNull AtomicReference<RealtyTags> realtyTags,
                 @NotNull AtomicReference<TaxSettings> taxSettings,
                 @NotNull ExecutorState executorState,
                 @NotNull Database database,
                 @NotNull RealtyBackend backend,
                 @NotNull RealtyPaperApi paperApi,
                 @NotNull EconomyProvider economyProvider,
                 @NotNull PartyService partyService,
                 @NotNull RegionProfileService regionProfileService,
                 @NotNull SignCache signCache,
                 @NotNull SignTextApplicator signTextApplicator,
                 @NotNull ProfileApplicator profileApplicator,
                 @NotNull RealtyEventDispatch eventDispatch,
                 @NotNull SquirrelIdUsernameResolver nameResolver,
                 @NotNull SafeLocationFinder safeLocationFinder,
                 @NotNull SubregionWand subregionWand,
                 @NotNull SubregionWandManager subregionWandManager) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.messages = messages;
        this.settings = settings;
        this.regionProfileSettings = regionProfileSettings;
        this.realtyTags = realtyTags;
        this.taxSettings = taxSettings;
        this.executorState = executorState;
        this.database = database;
        this.backend = backend;
        this.paperApi = paperApi;
        this.economyProvider = economyProvider;
        this.partyService = partyService;
        this.regionProfileService = regionProfileService;
        this.signCache = signCache;
        this.signTextApplicator = signTextApplicator;
        this.profileApplicator = profileApplicator;
        this.eventDispatch = eventDispatch;
        this.nameResolver = nameResolver;
        this.safeLocationFinder = safeLocationFinder;
        this.subregionWand = subregionWand;
        this.subregionWandManager = subregionWandManager;
    }

    @Override
    protected void configure() {
        bind(Realty.class).toInstance(this.plugin);
        bind(Logger.class).toInstance(this.logger);
        bind(Message.class).toInstance(this.messages);
        bind(ExecutorState.class).toInstance(this.executorState);
        bind(Database.class).toInstance(this.database);
        bind(RealtyBackend.class).toInstance(this.backend);
        bind(RealtyPaperApi.class).toInstance(this.paperApi);
        bind(EconomyProvider.class).toInstance(this.economyProvider);
        bind(PartyService.class).toInstance(this.partyService);
        bind(RegionProfileService.class).toInstance(this.regionProfileService);
        bind(SignCache.class).toInstance(this.signCache);
        bind(SignTextApplicator.class).toInstance(this.signTextApplicator);
        bind(ProfileApplicator.class).toInstance(this.profileApplicator);
        bind(RealtyEventDispatch.class).toInstance(this.eventDispatch);
        bind(SquirrelIdUsernameResolver.class).toInstance(this.nameResolver);
        bind(SafeLocationFinder.class).toInstance(this.safeLocationFinder);
        bind(SubregionWand.class).toInstance(this.subregionWand);
        bind(SubregionWandManager.class).toInstance(this.subregionWandManager);

        bind(new TypeLiteral<AtomicReference<Settings>>() {
        }).toInstance(this.settings);
        bind(new TypeLiteral<AtomicReference<RegionProfileSettings>>() {
        }).toInstance(this.regionProfileSettings);
        bind(new TypeLiteral<AtomicReference<RealtyTags>>() {
        }).toInstance(this.realtyTags);
        bind(new TypeLiteral<AtomicReference<TaxSettings>>() {
        }).toInstance(this.taxSettings);
    }
}
