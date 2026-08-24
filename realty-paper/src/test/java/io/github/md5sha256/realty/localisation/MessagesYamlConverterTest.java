package io.github.md5sha256.realty.localisation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conversion from {@code messages.yml} to {@code messages.properties}.
 *
 * <p>The failure mode this guards against is silent: a rewrite that is too eager turns colour tags
 * into empty placeholders and strips 800 lines of formatting, and one that is too timid leaves a
 * real placeholder rendering as literal {@code <region>} to the player. Neither breaks the build,
 * and neither is visible until a server runs.</p>
 */
class MessagesYamlConverterTest {

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    private static YamlConfiguration bundled() throws IOException {
        try (InputStream stream = MessagesYamlConverterTest.class.getResourceAsStream("/messages.yml")) {
            Assertions.assertNotNull(stream, "messages.yml is missing from the jar");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }

    // ── the rewrite itself ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a runtime placeholder becomes a {name} placeholder")
    void convertsKnownPlaceholders() {
        Assertions.assertEquals("You bought {region} for {price}.",
                MessagesYamlConverter.convertPlaceholders("You bought <region> for <price>."));
    }

    @Test
    @DisplayName("MiniMessage formatting is left alone")
    void leavesFormattingTags() {
        String input = "<prefix> <red>You have <green>bought</green> <yellow><region>.</yellow>";
        String converted = MessagesYamlConverter.convertPlaceholders(input);

        Assertions.assertTrue(converted.contains("<red>"), "colour tags must survive");
        Assertions.assertTrue(converted.contains("<green>"));
        Assertions.assertTrue(converted.contains("</green>"), "closing tags must survive");
        Assertions.assertTrue(converted.contains("{region}"));
        Assertions.assertTrue(converted.contains("{prefix}"));
    }

    @Test
    @DisplayName("command-usage text in the help pages stays literal")
    void leavesCommandUsageText() {
        // These render to the player as the syntax of the command. Converting them would replace
        // documented usage with empty placeholders.
        String usage = "/realty auction <bidduration> <paymentduration> <minbid> <minbidstep> <region>";
        String converted = MessagesYamlConverter.convertPlaceholders(usage);

        Assertions.assertTrue(converted.contains("<bidduration>"));
        Assertions.assertTrue(converted.contains("<paymentduration>"));
        Assertions.assertTrue(converted.contains("<minbid>"));
        Assertions.assertTrue(converted.contains("<minbidstep>"));
        // ...but <region> in the same line is a real placeholder and does convert.
        Assertions.assertTrue(converted.contains("{region}"));
    }

    @Test
    @DisplayName("a placeholder inside a click-tag argument converts too")
    void convertsPlaceholdersInsideTagArguments() {
        // These are filled by textual substitution rather than a resolver, because MiniMessage
        // cannot fill a tag argument from a TagResolver.
        Assertions.assertEquals("<click:run_command:{command}><yellow>»</yellow></click>",
                MessagesYamlConverter.convertPlaceholders(
                        "<click:run_command:<command>><yellow>»</yellow></click>"));
    }

    @Test
    @DisplayName("hex colours and gradients survive")
    void leavesHexAndGradients() {
        String input = "<gradient:#00aaff:#008EFF><b>Realty</b></gradient> <#00aaff>»</#00aaff>";

        Assertions.assertEquals(input, MessagesYamlConverter.convertPlaceholders(input));
    }

    // ── file shape ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("nested keys flatten and the shared prefix moves to the placeholder palette")
    void flattensAndRelocatesPrefix() {
        Map<String, String> flat = MessagesYamlConverter.flatten(yaml("""
                prefix: "<gradient:#00aaff:#008EFF><b>Realty</b></gradient>"
                buy:
                  success: "<prefix> Bought <region>."
                  error: "Failed: <error>"
                """));

        Assertions.assertTrue(flat.containsKey("placeholder.prefix"),
                "the prefix must land in the global palette so {prefix} resolves");
        Assertions.assertFalse(flat.containsKey("prefix"));
        Assertions.assertEquals("{prefix} Bought {region}.", flat.get("buy.success"));
        Assertions.assertEquals("Failed: {error}", flat.get("buy.error"));
    }

    @Test
    @DisplayName("a list-valued message joins into one newline-separated value")
    void joinsListValues() {
        Map<String, String> flat = MessagesYamlConverter.flatten(yaml("""
                help:
                  main:
                    - "line one"
                    - "line two <region>"
                """));

        Assertions.assertEquals("line one\nline two {region}", flat.get("help.main"));
    }

    @Test
    @DisplayName("the emitted text reloads through Properties with newlines intact")
    void emittedTextRoundTrips() throws IOException {
        String text = MessagesYamlConverter.convert(yaml("""
                help:
                  main:
                    - "line one"
                    - "line two"
                buy:
                  success: "Bought <region>"
                """));

        Properties props = new Properties();
        props.load(new StringReader(text));

        Assertions.assertEquals("line one\nline two", props.getProperty("help.main"),
                "a multi-line message must survive the properties round trip");
        Assertions.assertEquals("Bought {region}", props.getProperty("buy.success"));
    }

    // ── the real file ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("converting the bundled messages.yml leaves no unconverted runtime placeholder")
    void bundledFileHasNoStrandedPlaceholders() throws IOException {
        Map<String, String> flat = MessagesYamlConverter.flatten(bundled());

        // Any surviving <tag> must be either MiniMessage formatting or documented command usage.
        List<String> stranded = new ArrayList<>();
        Pattern tag = Pattern.compile("<([a-zA-Z0-9_]+)>");
        for (Map.Entry<String, String> entry : flat.entrySet()) {
            Matcher matcher = tag.matcher(entry.getValue());
            while (matcher.find()) {
                if (MessagesYamlConverter.PLACEHOLDERS.contains(matcher.group(1))) {
                    stranded.add(entry.getKey() + " -> <" + matcher.group(1) + ">");
                }
            }
        }
        Assertions.assertTrue(stranded.isEmpty(),
                "these placeholders would render literally to the player: " + stranded);
    }

    @Test
    @DisplayName("every MessageKeys constant survives the conversion")
    void everyMessageKeySurvives() throws Exception {
        Map<String, String> flat = MessagesYamlConverter.flatten(bundled());

        List<String> missing = new ArrayList<>();
        for (Field field : MessageKeys.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String key = (String) field.get(null);
            if (!flat.containsKey(key)) {
                missing.add(key);
            }
        }
        Assertions.assertTrue(missing.isEmpty(),
                "message keys lost in conversion: " + missing);
    }

    @Test
    @DisplayName("the converted bundle reloads through Properties")
    void bundledConversionRoundTrips() throws IOException {
        String text = MessagesYamlConverter.convert(bundled());

        Properties props = new Properties();
        Assertions.assertDoesNotThrow(() -> props.load(new StringReader(text)));
        Assertions.assertFalse(props.isEmpty());
        Assertions.assertNotNull(props.getProperty("placeholder.prefix"));
    }
}
