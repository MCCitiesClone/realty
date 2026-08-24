package io.github.md5sha256.realty;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.InstanceBinding;
import io.github.md5sha256.realty.command.RealtyCommands;
import io.github.md5sha256.realty.dialog.SearchDialogHandler;
import io.github.md5sha256.realty.dialog.SubregionDialogHandler;
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
import io.github.md5sha256.realty.listener.RealtyListeners;
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.settings.RegionProfileSettings;
import io.github.md5sha256.realty.settings.RegionTagSettings;
import io.github.md5sha256.realty.settings.Settings;
import io.github.md5sha256.realty.settings.TaxSettings;
import io.github.md5sha256.realty.util.SquirrelIdUsernameResolver;
import io.github.md5sha256.realty.wand.SubregionWand;
import io.github.md5sha256.realty.wand.SubregionWandManager;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import io.paradaux.hibernia.framework.configurator.ConfigurationProcessor;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.events.ListenerManager;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.hibernia.framework.usher.DialogManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Checks the two Guice modules the plugin actually starts with — {@link HiberniaModule} as
 * {@code onLoad} builds it, and the real {@link RealtyModule} — agree with each other.
 *
 * <p>{@code CommandRegistrationTest} builds the object graph from a hand-written stand-in module,
 * which proves handlers can be satisfied but says nothing about the production pair. Two startup
 * failures got through that gap: a {@code Logger} binding that duplicated Guice's built-in one, and
 * a {@code Message} binding that was both a duplicate of the framework's and {@code null}, because
 * the field it read is not assigned until the injector hands the instance back. Neither is
 * reachable from a stand-in; both abort {@code onEnable} on the first real start.</p>
 *
 * <p>The bindings are inspected through {@link Elements}, which runs {@code configure()} and
 * records what each module declares without instantiating anything — so this stays a unit test
 * while still reading the real declarations.</p>
 */
class InjectorConfigurationTest {

    /** The files {@code onLoad} copies out of the jar before the configurator reads them. */
    private static final String[] BUNDLED_CONFIG =
            {"settings.yml", "profiles.yml", "taxes.yml", "region-tags.yml", "database.yml"};

    @TempDir
    Path dataFolder;

    private Realty plugin;

    @BeforeEach
    void setUp() throws Exception {
        this.plugin = Mockito.mock(Realty.class);
        Mockito.when(this.plugin.getLogger()).thenReturn(Logger.getLogger("injector-config-test"));
        Mockito.when(this.plugin.getDataFolder()).thenReturn(this.dataFolder.toFile());
        Mockito.when(this.plugin.getConfig()).thenReturn(new YamlConfiguration());
        Server server = Mockito.mock(Server.class);
        Mockito.when(server.getScheduler()).thenReturn(Mockito.mock(BukkitScheduler.class));
        Mockito.when(server.isPrimaryThread()).thenReturn(true);
        Mockito.when(server.getPluginManager()).thenReturn(Mockito.mock(PluginManager.class));
        Mockito.when(this.plugin.getServer()).thenReturn(server);
        Mockito.when(this.plugin.getResource(Mockito.anyString())).thenAnswer(
                invocation -> getClass().getResourceAsStream("/" + invocation.getArgument(0)));
        // The configurator reads these out of the data folder; onLoad puts them there.
        for (String name : BUNDLED_CONFIG) {
            try (InputStream stream = getClass().getResourceAsStream("/" + name)) {
                if (stream != null) {
                    Files.copy(stream, this.dataFolder.resolve(name));
                }
            }
        }
    }

    /** Builds the Hibernia module with exactly the wiring {@link Realty#onLoad()} uses. */
    private HiberniaModule hiberniaModule() {
        return HiberniaModule.forPlugin(this.plugin)
                .scanConfiguration("io.github.md5sha256.realty.settings")
                .scanConfiguration("io.github.md5sha256.realty")
                .handlers(RealtyCommands.handlers())
                .resolvers(RealtyCommands.resolvers())
                .listeners(RealtyListeners.listeners())
                .dialogs(SearchDialogHandler.class, SubregionDialogHandler.class)
                .build();
    }

    /** Settings records cannot be mocked, so bind them on their own defaults. */
    private static <T> T defaults(Class<T> type) {
        Plugin owner = Mockito.mock(Plugin.class);
        Mockito.when(owner.getLogger()).thenReturn(Logger.getLogger("injector-config-test"));
        return type.cast(new ConfigurationProcessor(owner).create(type, new YamlConfiguration()));
    }

    /**
     * The real {@link RealtyModule}, built the way {@code createInjector} builds it. Services are
     * mocks; what matters here is which keys the module declares and whether any instance is null.
     */
    private RealtyModule realtyModule() {
        return new RealtyModule(
                this.plugin,
                new AtomicReference<>(defaults(Settings.class)),
                new AtomicReference<>(defaults(RegionProfileSettings.class)),
                new AtomicReference<>(new RealtyTags(defaults(RegionTagSettings.class))),
                new AtomicReference<>(defaults(TaxSettings.class)),
                new ExecutorState(
                        Runnable::run,
                        MoreExecutors.newDirectExecutorService(),
                        MoreExecutors.newDirectExecutorService()),
                Mockito.mock(Database.class),
                Mockito.mock(RealtyBackend.class),
                Mockito.mock(RealtyPaperApi.class),
                Mockito.mock(EconomyProvider.class),
                Mockito.mock(PartyService.class),
                Mockito.mock(RegionProfileService.class),
                Mockito.mock(SignCache.class),
                Mockito.mock(SignTextApplicator.class),
                Mockito.mock(ProfileApplicator.class),
                Mockito.mock(RealtyEventDispatch.class),
                Mockito.mock(SquirrelIdUsernameResolver.class),
                Mockito.mock(SafeLocationFinder.class),
                Mockito.mock(SubregionWand.class),
                Mockito.mock(SubregionWandManager.class),
                mockModuleManager());
    }

    @SuppressWarnings("unchecked")
    private static ModuleLifecycleManager<Realty> mockModuleManager() {
        return Mockito.mock(ModuleLifecycleManager.class);
    }

    @Test
    @DisplayName("no key is bound by both RealtyModule and HiberniaModule")
    void productionModulesDoNotCollide() {
        List<Element> elements = Elements.getElements(hiberniaModule(), realtyModule());

        Map<Key<?>, Integer> counts = new HashMap<>();
        for (Element element : elements) {
            if (element instanceof Binding<?> binding) {
                counts.merge(binding.getKey(), 1, Integer::sum);
            }
        }
        List<String> duplicated = new ArrayList<>();
        counts.forEach((key, count) -> {
            if (count > 1) {
                duplicated.add(key + " (bound " + count + " times)");
            }
        });
        Assertions.assertTrue(duplicated.isEmpty(),
                "these keys are bound more than once, which fails injector creation at startup: "
                        + duplicated);
    }

    @Test
    @DisplayName("no binding supplies a null instance")
    void noBindingIsNull() {
        List<Element> elements = Elements.getElements(hiberniaModule(), realtyModule());

        List<String> nulls = new ArrayList<>();
        for (Element element : elements) {
            if (element instanceof InstanceBinding<?> binding && binding.getInstance() == null) {
                nulls.add(binding.getKey().toString());
            }
        }
        Assertions.assertTrue(nulls.isEmpty(),
                "these keys are bound to null, which fails injector creation at startup: " + nulls);
    }

    /**
     * Provisions what {@code onEnable} provisions, from the modules it provisions them with.
     *
     * <p>The two checks above read declarations without instantiating, so they cannot see an error
     * that only arises when Guice walks the graph. A dependency cycle is exactly that: Realty's
     * subregion flow injected {@code DialogManager}, which injects the set of dialog handlers,
     * which contains the handler that needs the flow. Guice breaks such a cycle only by proxying an
     * interface, and {@code DialogManager} is a concrete class, so provisioning failed and aborted
     * {@code onEnable} — with every binding perfectly well-formed.</p>
     */
    @Test
    @DisplayName("the managers onEnable asks for can actually be provisioned")
    void managersProvision() {
        Injector injector = Guice.createInjector(hiberniaModule(), realtyModule());

        Assertions.assertNotNull(injector.getInstance(CommandManager.class));
        Assertions.assertNotNull(injector.getInstance(ListenerManager.class));
        Assertions.assertNotNull(injector.getInstance(DialogManager.class));

        // onEnable registers listeners straight after provisioning; the set is built by the
        // multibinder, so a listener that cannot be constructed only shows up here.
        injector.getInstance(ListenerManager.class).registerAll();
    }
}
