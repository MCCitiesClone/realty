package io.github.md5sha256.realty.localisation;

import io.github.md5sha256.realty.DatabaseSettings;
import io.github.md5sha256.realty.settings.ConfigRegionTag;
import io.github.md5sha256.realty.settings.RegionProfileSettings;
import io.github.md5sha256.realty.settings.RegionTagSettings;
import io.github.md5sha256.realty.settings.Settings;
import io.github.md5sha256.realty.settings.TaxSettings;
import io.paradaux.hibernia.framework.configurator.ConfigurationProcessor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Guards the configuration files bundled in the jar.
 *
 * <p>These are parsed during {@code onLoad}, so a malformed one takes the whole plugin down before
 * it can start — and nothing else in the build reads them, so the damage only surfaces on a server.
 * A message value containing an unquoted {@code ": "} shipped exactly that way once: YAML read the
 * colon as a nested mapping and the plugin failed to load.
 */
class BundledResourceTest {

    /**
     * Loads a bundled config the way the plugin does — Bukkit's YAML parser, which is what
     * {@code ConfigurationLoader} reads these files with.
     */
    private static YamlConfiguration load(String resourceName) throws IOException {
        try (InputStream stream = BundledResourceTest.class
                .getResourceAsStream("/" + resourceName + ".yml")) {
            Assertions.assertNotNull(stream, resourceName + ".yml is missing from the jar");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    private static ConfigurationProcessor processor() {
        Plugin plugin = Mockito.mock(Plugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Mockito.mock(Logger.class));
        return new ConfigurationProcessor(plugin);
    }

    /** Every resource the plugin reads at startup. */
    @ParameterizedTest
    @ValueSource(strings = {"settings", "database", "profiles", "region-tags", "taxes"})
    @DisplayName("every bundled config parses as YAML")
    void bundledConfigParses(String resourceName) {
        Assertions.assertDoesNotThrow(() -> load(resourceName),
                resourceName + ".yml does not parse, so the plugin would fail to load. A value "
                        + "containing \": \" must be quoted.");
    }

    private static List<String> declaredMessageKeys() throws IllegalAccessException {
        List<String> declared = new ArrayList<>();
        for (Field field : MessageKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                declared.add((String) field.get(null));
            }
        }
        return declared;
    }

    @Test
    @DisplayName("every MessageKeys constant resolves to a value in messages.properties")
    void everyMessageKeyIsDefined() throws Exception {
        Properties messages = new Properties();
        try (InputStream stream = BundledResourceTest.class.getResourceAsStream("/messages.properties")) {
            Assertions.assertNotNull(stream, "messages.properties is missing from the jar");
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                messages.load(reader);
            }
        }

        List<String> missing = new ArrayList<>();
        for (String key : declaredMessageKeys()) {
            if (messages.getProperty(key) == null) {
                missing.add(key);
            }
        }
        // Also catches structural damage: a mis-quoted value swallows its siblings, so their keys
        // stop resolving even while the file still loads.
        Assertions.assertTrue(missing.isEmpty(),
                "message keys with no value in messages.properties: " + missing);
    }

    /**
     * Parsing a config is not the same as being able to use it: a setting missing from the bundled
     * defaults, or one whose path no longer matches the record it feeds, binds to nothing and only
     * shows up on a running server. This maps each file onto its component exactly as
     * {@code ConfigurationLoader} does at startup, and asserts the values actually arrived —
     * a non-null record alone would still pass with every component silently null.
     */
    @Test
    @DisplayName("every bundled config binds onto the component startup maps it to")
    void bundledConfigsBind() throws IOException {
        ConfigurationProcessor processor = processor();

        Settings settings = (Settings) processor.create(Settings.class, load("settings"));
        Assertions.assertNotNull(settings, "settings");
        Assertions.assertNotNull(settings.defaultFreeholdAuthority(),
                "settings.yml: default-freehold-authority-uuid did not bind");
        Assertions.assertNotNull(settings.defaultLeaseholdAuthority(),
                "settings.yml: default-leasehold-authority-uuid did not bind");
        Assertions.assertNotNull(settings.dateFormat(), "settings.yml: date-format did not bind");
        Assertions.assertEquals("GOLDEN_AXE", settings.subregionWandMaterial(),
                "settings.yml: subregion-wand-material did not bind");
        Assertions.assertTrue(settings.teleportStartHeight() > 0,
                "settings.yml: teleportation-starting-height did not bind");

        DatabaseSettings database =
                (DatabaseSettings) processor.create(DatabaseSettings.class, load("database"));
        Assertions.assertNotNull(database, "database");
        // Ships blank on purpose — that is how startup detects an unconfigured database.
        Assertions.assertEquals("", database.url(), "database.yml: url should ship empty");

        TaxSettings taxes = (TaxSettings) processor.create(TaxSettings.class, load("taxes"));
        Assertions.assertNotNull(taxes, "taxes");
        Assertions.assertTrue(taxes.enabled(), "taxes.yml: enabled did not bind");
        Assertions.assertEquals("DCGovernment", taxes.governmentAccount(),
                "taxes.yml: government-account did not bind");
        Assertions.assertEquals(7, taxes.exemptPlotThreshold(),
                "taxes.yml: exempt-plot-threshold did not bind");
        Assertions.assertEquals(TaxSettings.DEFAULT_FORMULA, taxes.defaultFormula(),
                "taxes.yml: default-formula did not bind");

        RegionTagSettings tags =
                (RegionTagSettings) processor.create(RegionTagSettings.class, load("region-tags"));
        Assertions.assertNotNull(tags, "region-tags");
        Assertions.assertEquals(3, tags.tags().size(), "region-tags.yml: tag list did not bind");
        ConfigRegionTag first = tags.tags().getFirst();
        Assertions.assertEquals("residential", first.tagId(), "region-tags.yml: tag-id did not bind");
        Assertions.assertNotNull(first.tagDisplayName(),
                "region-tags.yml: tag-display-name did not bind");
        Assertions.assertNotNull(first.permission(), "region-tags.yml: permission did not bind");
        Assertions.assertEquals("realty.tag.residential", first.permission().node(),
                "region-tags.yml: permission.node did not bind");

        // profiles.yml ships as an all-comments template, so it maps to an empty profile set
        // rather than to populated one; mapping it must still not throw.
        Assertions.assertDoesNotThrow(
                () -> processor.create(RegionProfileSettings.class, load("profiles")), "profiles");
    }
}
