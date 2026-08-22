package io.github.md5sha256.realty.localisation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
     * Loads a bundled config the way {@code Realty.copyDefaultsYaml} does. The serializers the
     * plugin registers are irrelevant here — a syntax error fails before any value is read.
     */
    private static ConfigurationNode load(String resourceName) throws IOException {
        try (InputStream stream = BundledResourceTest.class
                .getResourceAsStream("/" + resourceName + ".yml")) {
            Assertions.assertNotNull(stream, resourceName + ".yml is missing from the jar");
            return YamlConfigurationLoader.builder()
                    .source(() -> new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8)))
                    .build()
                    .load();
        }
    }

    /** Every resource {@code Realty.copyDefaultsYaml} loads at startup. */
    @ParameterizedTest
    @ValueSource(strings = {"messages", "settings", "database", "profiles", "region-tags", "taxes"})
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
    @DisplayName("every MessageKeys constant resolves to a value in messages.yml")
    void everyMessageKeyIsDefined() throws Exception {
        ConfigurationNode root = load("messages");
        List<String> missing = new ArrayList<>();
        for (String key : declaredMessageKeys()) {
            ConfigurationNode node = root.node((Object[]) key.split("\\."));
            // A message is either one line or a list of them (help pages, info blocks), so this
            // asks only that something is there — not that it is a scalar.
            if (node.virtual() || node.empty()) {
                missing.add(key);
            }
        }
        // Also catches structural damage: a mis-quoted value swallows its siblings into another
        // node, so their keys stop resolving even while the file still parses.
        Assertions.assertTrue(missing.isEmpty(),
                "message keys with no value in messages.yml: " + missing);
    }

}
