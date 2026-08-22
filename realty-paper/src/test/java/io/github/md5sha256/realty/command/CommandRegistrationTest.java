package io.github.md5sha256.realty.command;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.minecraftcitiesnetwork.pluginInfrastructure.modules.ModuleLifecycleManager;
import io.github.md5sha256.realty.Realty;
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
import io.github.md5sha256.realty.party.PartyService;
import io.github.md5sha256.realty.settings.RealtyTags;
import io.github.md5sha256.realty.settings.RegionProfileSettings;
import io.github.md5sha256.realty.settings.RegionTagSettings;
import io.github.md5sha256.realty.settings.Settings;
import io.github.md5sha256.realty.settings.TaxSettings;
import io.github.md5sha256.realty.util.SquirrelIdUsernameResolver;
import io.github.md5sha256.realty.wand.SubregionWand;
import io.github.md5sha256.realty.wand.SubregionWandManager;
import io.papermc.paper.command.brigadier.Commands;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.configurator.ConfigurationProcessor;
import io.paradaux.hibernia.framework.commander.RouteInfo;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.commander.spi.ParameterResolver;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.DialogManager;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Registers Realty's whole command tree the way the server does, and asserts it comes up clean.
 *
 * <p>The framework validates routes at registration rather than at execution: a placeholder with no
 * matching parameter, an optional segment that is not the tail, two routes executing at the same
 * path, an argument declared as two different Brigadier types. All of that is caught here instead
 * of on a running server — and it is caught <em>quietly</em> in production, because a handler that
 * fails to bind is logged and skipped so the rest still register. A test that only asserted "no
 * exception" would therefore pass with half the commands missing, so this asserts on the log and on
 * the resulting route index.</p>
 */
class CommandRegistrationTest {

    /** Collects what the framework logs while registering. */
    private static final class RecordingHandler extends java.util.logging.Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            this.records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        List<LogRecord> atLeast(Level level) {
            return this.records.stream()
                    .filter(r -> r.getLevel().intValue() >= level.intValue())
                    .toList();
        }
    }

    private Realty plugin;
    private RecordingHandler logs;
    private Injector injector;

    @BeforeEach
    void setUp() {
        this.plugin = Mockito.mock(Realty.class);
        Server server = Mockito.mock(Server.class);
        this.logs = new RecordingHandler();

        Logger logger = Logger.getLogger("realty-command-registration-test");
        logger.setUseParentHandlers(false);
        for (java.util.logging.Handler existing : logger.getHandlers()) {
            logger.removeHandler(existing);
        }
        logger.addHandler(this.logs);

        Mockito.when(this.plugin.getLogger()).thenReturn(logger);
        Mockito.when(this.plugin.getServer()).thenReturn(server);
        Mockito.when(server.getScheduler()).thenReturn(Mockito.mock(BukkitScheduler.class));
        Mockito.when(server.isPrimaryThread()).thenReturn(true);

        this.injector = Guice.createInjector(new TestServicesModule(this.plugin));
    }

    /**
     * Binds every service a handler may ask for to a mock. Handlers are thin — they consume
     * services rather than reaching into them at construction — so a mock is enough to build the
     * real object graph and register the real routes.
     */
    private static final class TestServicesModule extends AbstractModule {
        private final Realty plugin;

        TestServicesModule(Realty plugin) {
            this.plugin = plugin;
        }

        /**
         * Builds a settings record on its own defaults. These are records, so they cannot be
         * mocked — and binding them from empty configuration exercises the same defaulting the
         * plugin relies on at startup.
         */
        private static <T> T defaults(Class<T> type) {
            Plugin owner = Mockito.mock(Plugin.class);
            Mockito.when(owner.getLogger()).thenReturn(Logger.getLogger("registration-test-config"));
            return type.cast(new ConfigurationProcessor(owner)
                    .create(type, new YamlConfiguration()));
        }

        @Override
        protected void configure() {
            bind(Realty.class).toInstance(this.plugin);
            bind(Message.class).toInstance(Mockito.mock(Message.class));
            // A record, so not mockable; direct executors keep any scheduled work on this thread.
            bind(ExecutorState.class).toInstance(new ExecutorState(
                    Runnable::run, MoreExecutors.newDirectExecutorService(),
                    MoreExecutors.newDirectExecutorService()));
            bind(Database.class).toInstance(Mockito.mock(Database.class));
            bind(RealtyBackend.class).toInstance(Mockito.mock(RealtyBackend.class));
            bind(RealtyPaperApi.class).toInstance(Mockito.mock(RealtyPaperApi.class));
            bind(EconomyProvider.class).toInstance(Mockito.mock(EconomyProvider.class));
            bind(PartyService.class).toInstance(Mockito.mock(PartyService.class));
            bind(RegionProfileService.class).toInstance(Mockito.mock(RegionProfileService.class));
            bind(SignCache.class).toInstance(Mockito.mock(SignCache.class));
            bind(SignTextApplicator.class).toInstance(Mockito.mock(SignTextApplicator.class));
            bind(ProfileApplicator.class).toInstance(Mockito.mock(ProfileApplicator.class));
            bind(RealtyEventDispatch.class).toInstance(Mockito.mock(RealtyEventDispatch.class));
            bind(SquirrelIdUsernameResolver.class)
                    .toInstance(Mockito.mock(SquirrelIdUsernameResolver.class));
            bind(SafeLocationFinder.class).toInstance(Mockito.mock(SafeLocationFinder.class));
            bind(SubregionWand.class).toInstance(Mockito.mock(SubregionWand.class));
            bind(SubregionWandManager.class).toInstance(Mockito.mock(SubregionWandManager.class));
            // The dialog tier is not what this test registers; a stub keeps its wiring out of
            // the way of the command tree.
            bind(DialogManager.class).toInstance(Mockito.mock(DialogManager.class));
            bind(new TypeLiteral<ModuleLifecycleManager<Realty>>() {
            }).toInstance(Mockito.mock(ModuleLifecycleManager.class));
            bind(new TypeLiteral<AtomicReference<Settings>>() {
            }).toInstance(new AtomicReference<>(defaults(Settings.class)));
            bind(new TypeLiteral<AtomicReference<RegionProfileSettings>>() {
            }).toInstance(new AtomicReference<>(defaults(RegionProfileSettings.class)));
            bind(new TypeLiteral<AtomicReference<RealtyTags>>() {
            }).toInstance(new AtomicReference<>(
                    new RealtyTags(defaults(RegionTagSettings.class))));
            bind(new TypeLiteral<AtomicReference<TaxSettings>>() {
            }).toInstance(new AtomicReference<>(defaults(TaxSettings.class)));
        }
    }

    /** Registers every handler and returns the resulting route index. */
    private List<RouteInfo> register() {
        Set<CommandHandler> handlers = RealtyCommands.HANDLERS.stream()
                .map(type -> (CommandHandler) this.injector.getInstance(type))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<ParameterResolver<?>> resolvers = RealtyCommands.RESOLVERS.stream()
                .map(type -> (ParameterResolver<?>) this.injector.getInstance(type))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        CommandManager manager = new CommandManager(this.plugin, handlers, resolvers);

        // register(Commands) rather than registerAll(): the latter only hooks Paper's command
        // lifecycle, whose event types need a live server to initialise. This is the same code
        // path, minus the hook.
        manager.register(Mockito.mock(Commands.class));

        return manager.routeIndex();
    }

    @Test
    @DisplayName("every command handler registers without being skipped")
    void everyHandlerRegisters() {
        register();

        List<String> failures = this.logs.atLeast(Level.SEVERE).stream()
                .map(LogRecord::getMessage)
                .toList();
        // A handler that fails validation is logged and skipped so the others still register;
        // without this assertion the tree could come up half-missing and the test would pass.
        Assertions.assertTrue(failures.isEmpty(),
                "handlers were skipped during registration: " + failures);
    }

    @Test
    @DisplayName("registration reports no ambiguous or conflicting routes")
    void registrationIsUnambiguous() {
        register();

        List<String> warnings = this.logs.atLeast(Level.WARNING).stream()
                .map(LogRecord::getMessage)
                .filter(message -> message != null && message.contains("Ambiguous"))
                .toList();
        Assertions.assertTrue(warnings.isEmpty(), "ambiguous sibling routes: " + warnings);
    }

    @Test
    @DisplayName("every route is registered under /realty and its /rl alias")
    void everyRouteIsUnderBothRoots() {
        List<RouteInfo> routes = register();
        Set<String> roots = routes.stream().map(RouteInfo::root).collect(Collectors.toCollection(TreeSet::new));

        Assertions.assertEquals(Set.of("realty", "rl"), roots,
                "the tree should be registered under the command and its alias, and nothing else");

        Set<String> underRealty = routes.stream()
                .filter(route -> "realty".equals(route.root()))
                .map(RouteInfo::pattern)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> underAlias = routes.stream()
                .filter(route -> "rl".equals(route.root()))
                .map(RouteInfo::pattern)
                .collect(Collectors.toCollection(TreeSet::new));
        Assertions.assertEquals(underRealty, underAlias,
                "/rl must expose exactly the same routes as /realty");
    }

    @Test
    @DisplayName("every route's permission is declared in paper-plugin.yml")
    void everyRoutePermissionIsDeclared() throws IOException {
        Set<String> declared = declaredPermissions();

        List<String> undeclared = register().stream()
                .map(RouteInfo::permission)
                .filter(permission -> permission != null && !permission.isEmpty())
                .distinct()
                .filter(permission -> !declared.contains(permission))
                .sorted()
                .toList();

        // An undeclared node has no default, and Bukkit's default is OP — so this silently
        // restricts a command to operators rather than failing.
        Assertions.assertEquals(List.of(), undeclared,
                "route permissions missing from paper-plugin.yml");
    }

    @Test
    @DisplayName("every route resolves a description for the help output")
    void everyRouteIsDescribed() {
        List<String> undescribed = register().stream()
                .filter(route -> route.description() == null || route.description().isBlank())
                .map(RouteInfo::pattern)
                .sorted()
                .toList();

        Assertions.assertEquals(List.of(), undescribed, "routes with no @Description");
    }

    @Test
    @DisplayName("the ported tree still covers every command the Cloud tree exposed")
    void everyLegacyRouteSurvives() {
        Set<String> patterns = register().stream()
                .filter(route -> "realty".equals(route.root()))
                .map(RouteInfo::pattern)
                .collect(Collectors.toCollection(TreeSet::new));

        // Spot-checks the shapes most at risk of being dropped in a hand port: the optional-tail
        // fallback, a required-only route, and each multi-literal family.
        for (String expected : List.of(
                "buy [region]", "rent [region]", "unrent [region]", "extend [region]",
                "rentable <accepting> [region]", "add <player> [region]", "remove <player> [region]",
                "delete <region> [includeworldguard]", "transfer <titleholder> [region]",
                "tp <region>", "info [region]", "history [region]", "version", "reload",
                "agent invite <player> [region]", "agent invite accept [region]",
                "agent invite reject [region]", "agent invite withdraw <player> [region]",
                "agent remove <player> [region]")) {
            Assertions.assertTrue(patterns.contains(expected),
                    "route disappeared in the port: '" + expected + "' (have " + patterns + ")");
        }
    }

    @Test
    @DisplayName("every message key a handler names exists in messages.properties")
    void everyHandlerMessageKeyExists() throws IOException {
        Properties messages = new Properties();
        try (InputStream stream = getClass().getResourceAsStream("/messages.properties")) {
            Assertions.assertNotNull(stream, "messages.properties is missing");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
        }

        Set<String> missing = new TreeSet<>();
        for (Class<?> handler : RealtyCommands.HANDLERS) {
            for (java.lang.reflect.Field field : handler.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(null);
                    if (value instanceof String key && key.contains(".")
                            && key.matches("[a-z0-9.\\-]+") && messages.getProperty(key) == null) {
                        missing.add(key);
                    }
                } catch (IllegalAccessException ignored) {
                    // A field we cannot read cannot name a missing key either.
                }
            }
        }
        Assertions.assertEquals(Set.of(), missing,
                "handlers reference message keys with no entry in messages.properties");
    }

    private static Set<String> declaredPermissions() throws IOException {
        try (InputStream stream = CommandRegistrationTest.class
                .getResourceAsStream("/paper-plugin.yml")) {
            Assertions.assertNotNull(stream, "paper-plugin.yml is missing from the jar");
            YamlConfiguration root;
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                root = YamlConfiguration.loadConfiguration(reader);
            }
            Set<String> declared = new HashSet<>();
            ConfigurationSection permissions = root.getConfigurationSection("permissions");
            if (permissions == null) {
                return declared;
            }
            // Bukkit splits dotted keys into a tree, so the literal node is never a direct child;
            // the real entries are the ones carrying default/description.
            for (String path : permissions.getKeys(true)) {
                ConfigurationSection node = permissions.getConfigurationSection(path);
                if (node != null && (node.contains("default") || node.contains("description"))) {
                    declared.add(path);
                }
            }
            return declared;
        }
    }
}
