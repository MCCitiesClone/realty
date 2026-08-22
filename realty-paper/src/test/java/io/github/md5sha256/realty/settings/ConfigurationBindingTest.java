package io.github.md5sha256.realty.settings;

import io.github.md5sha256.realty.api.RegionState;
import io.github.md5sha256.realty.api.SignProfile;
import io.paradaux.hibernia.framework.configurator.ConfigurationProcessor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Binds populated configuration onto the settings records.
 *
 * <p>{@code BundledResourceTest} covers the files as shipped, but {@code profiles.yml} ships as an
 * all-comments template — so the most intricate shape in the plugin, a state-keyed map of profiles
 * each carrying a flag map and a sign template, is exercised by nothing there. The YAML below is
 * the worked example from that file's own comments, which is what an operator copies.</p>
 */
class ConfigurationBindingTest {

    private ConfigurationProcessor processor;

    @BeforeEach
    void setUp() {
        Plugin plugin = Mockito.mock(Plugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Mockito.mock(Logger.class));
        this.processor = new ConfigurationProcessor(plugin);
    }

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    private static final String POPULATED_PROFILES = """
            global:
              ALL:
                flags:
                  pvp: deny
              FOR_SALE:
                priority: 5
                flags:
                  greeting: "This region is for sale!"
              SOLD:
                priority: 10
                flags:
                  pvp: "deny -g NON_MEMBERS"
                  greeting: "This region is owned."
              FOR_LEASE:
                priority: 5
                flags:
                  pvp: deny
                sign:
                  lines:
                    - "<blue><bold>[For Lease]"
                    - "<region>"
                    - "<duration>"
                    - "<price>"
                  right-click-commands:
                    - "realty rent <region>"
                  left-click-commands:
                    - "realty info <region>"
            grouped:
              - regions:
                  - market_stall_1
                  - market_stall_2
                states:
                  FOR_SALE:
                    priority: 15
                    flags:
                      pvp: deny
                      interact: "deny -g NON_MEMBERS"
                  SOLD:
                    priority: 20
                    flags:
                      pvp: deny
                      interact: allow
              - regions:
                  - apartment_101
                states:
                  LEASED:
                    flags:
                      use: allow
            """;

    @Test
    @DisplayName("profiles.yml binds its global state map, flags and sign template")
    void bindsGlobalProfiles() {
        RegionProfileSettings settings = (RegionProfileSettings)
                processor.create(RegionProfileSettings.class, yaml(POPULATED_PROFILES));

        Assertions.assertNotNull(settings.global());
        Assertions.assertEquals(4, settings.global().size());

        RegionProfile forSale = settings.global().get(RegionState.FOR_SALE);
        Assertions.assertNotNull(forSale, "FOR_SALE profile");
        Assertions.assertEquals(5, forSale.priority());
        Assertions.assertEquals("This region is for sale!", forSale.flags().get("greeting"));

        RegionProfile sold = settings.global().get(RegionState.SOLD);
        Assertions.assertEquals("deny -g NON_MEMBERS", sold.flags().get("pvp"),
                "region-group suffixes must survive binding verbatim");

        RegionProfile all = settings.global().get(RegionState.ALL);
        Assertions.assertNull(all.priority(), "an omitted priority stays null, not zero");

        SignProfile sign = settings.global().get(RegionState.FOR_LEASE).sign();
        Assertions.assertNotNull(sign, "FOR_LEASE sign template");
        Assertions.assertEquals(4, sign.lines().size());
        Assertions.assertEquals("<region>", sign.lines().get(1),
                "sign placeholders must not be interpreted at bind time");
        Assertions.assertEquals(List.of("realty rent <region>"), sign.rightClickCommands());
        Assertions.assertEquals(List.of("realty info <region>"), sign.leftClickCommands());
    }

    @Test
    @DisplayName("profiles.yml binds grouped overrides, each with its own region set")
    void bindsGroupedProfiles() {
        RegionProfileSettings settings = (RegionProfileSettings)
                processor.create(RegionProfileSettings.class, yaml(POPULATED_PROFILES));

        Assertions.assertNotNull(settings.grouped());
        Assertions.assertEquals(2, settings.grouped().size());

        GroupedRegionProfile stalls = settings.grouped().getFirst();
        Assertions.assertEquals(Set.of("market_stall_1", "market_stall_2"), stalls.regions());
        Assertions.assertEquals(15, stalls.states().get(RegionState.FOR_SALE).priority());
        Assertions.assertEquals("allow", stalls.states().get(RegionState.SOLD).flags().get("interact"));

        GroupedRegionProfile apartment = settings.grouped().get(1);
        Assertions.assertEquals(Set.of("apartment_101"), apartment.regions());
        Assertions.assertEquals("allow", apartment.states().get(RegionState.LEASED).flags().get("use"));
    }

    @Test
    @DisplayName("an all-comments profiles.yml binds to an empty profile set rather than failing")
    void emptyProfilesBind() {
        RegionProfileSettings settings = (RegionProfileSettings)
                processor.create(RegionProfileSettings.class, yaml("# nothing configured\n"));

        Assertions.assertNotNull(settings);
        Assertions.assertTrue(settings.global() == null || settings.global().isEmpty());
        Assertions.assertTrue(settings.grouped() == null || settings.grouped().isEmpty());
    }

    @Test
    @DisplayName("taxes.yml binds rule match predicates and formulas")
    void bindsTaxRules() {
        TaxSettings taxes = (TaxSettings) processor.create(TaxSettings.class, yaml("""
                enabled: true
                government-account: LocalGov
                exempt-uuids:
                  - "00000000-0000-0000-0000-000000000001"
                exempt-plot-threshold: 3
                default-formula: "2 * <plots>"
                rules:
                  - match:
                      all: [residential]
                    formula: "1.5 * <plots>"
                  - match:
                      any: [commercial, industrial]
                    formula: "3 * <plots>"
                """));

        Assertions.assertEquals("LocalGov", taxes.governmentAccount());
        Assertions.assertEquals(3, taxes.exemptPlotThreshold());
        Assertions.assertEquals(1, taxes.exemptUuids().size());
        Assertions.assertEquals(2, taxes.rules().size());

        TaxRule residential = taxes.rules().getFirst();
        Assertions.assertEquals(List.of("residential"), residential.match().all());
        Assertions.assertEquals("1.5 * <plots>", residential.formula());

        TaxRule commercial = taxes.rules().get(1);
        Assertions.assertEquals(List.of("commercial", "industrial"), commercial.match().any());
        Assertions.assertTrue(commercial.match().all().isEmpty());
    }

    @Test
    @DisplayName("region-tags.yml binds display names as MiniMessage and permission defaults as enums")
    void bindsRegionTags() {
        RegionTagSettings tags = (RegionTagSettings) processor.create(RegionTagSettings.class, yaml("""
                tags:
                  - tag-id: residential
                    tag-display-name: "<green>Residential"
                    permission:
                      node: realty.tag.residential
                      default: OP
                  - tag-id: public
                    tag-display-name: "<aqua>Public"
                    permission:
                      node: realty.tag.public
                      default: TRUE
                """));

        Assertions.assertEquals(2, tags.tags().size());
        ConfigRegionTag residential = tags.tags().getFirst();
        Assertions.assertEquals("Residential",
                PlainTextComponentSerializer.plainText().serialize(residential.tagDisplayName()));
        Assertions.assertEquals(TagPermission.PermissionDefault.OP,
                residential.permission().permissionDefault());

        // Unquoted TRUE is a YAML boolean, so it reaches the binder as "true" — it must still
        // match the constant named TRUE rather than leaving the permission default unset.
        Assertions.assertEquals(TagPermission.PermissionDefault.TRUE,
                tags.tags().get(1).permission().permissionDefault());
    }

    @Test
    @DisplayName("settings.yml compact-constructor defaults apply to configured values")
    void settingsDefaultsApply() {
        Settings settings = (Settings) processor.create(Settings.class, yaml("""
                default-freehold-authority-uuid: "00000000-0000-0000-0000-000000000000"
                default-leasehold-authority-uuid: "00000000-0000-0000-0000-000000000000"
                profile-reapply-per-tick: 0
                subregion-min-volume: -1
                offer-payment-duration-seconds: 0
                lease-termination-notice-seconds: 0
                subregion-wand-material: ""
                date-format: ""
                """));

        Assertions.assertEquals(10, settings.profileReapplyPerTick());
        Assertions.assertEquals(20, settings.subregionMinVolume());
        Assertions.assertEquals(86400L, settings.offerPaymentDurationSeconds());
        Assertions.assertEquals(604800L, settings.terminationNoticeSeconds());
        Assertions.assertEquals("GOLDEN_AXE", settings.subregionWandMaterial());
        Assertions.assertNotNull(settings.dateFormat());
    }
}
