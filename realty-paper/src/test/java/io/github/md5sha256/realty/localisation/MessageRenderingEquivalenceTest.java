package io.github.md5sha256.realty.localisation;

import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Proves the framework renders Realty's converted messages the way the old
 * {@code MessageContainer} rendered the originals, against the real bundled file.
 *
 * <p>This exists because the two systems agree on MiniMessage but not on how a value reaches the
 * text, and the differences are invisible until a player sees them: a value that should be inert
 * arriving as markup, a styled component flattened to plain text, or a click target that silently
 * stops working because its placeholder sits inside a tag argument where no resolver can reach.</p>
 */
class MessageRenderingEquivalenceTest {

    @TempDir
    Path dataFolder;

    private Message message;

    @BeforeEach
    void setUp() throws IOException {
        // Message copies the bundled messages.properties into the data folder on first run; give it
        // the real one so these assertions run against what actually ships.
        try (InputStream stream = getClass().getResourceAsStream("/messages.properties")) {
            Assertions.assertNotNull(stream, "messages.properties is missing from the jar");
            Files.copy(stream, dataFolder.resolve("messages.properties"));
        }
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        Mockito.when(plugin.getLogger()).thenReturn(Mockito.mock(Logger.class));
        Mockito.when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        Mockito.when(plugin.getResource("messages.properties")).thenAnswer(
                invocation -> getClass().getResourceAsStream("/messages.properties"));
        this.message = new Message(plugin);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a plain value renders as text and the surrounding formatting survives")
    void plainValueRenders() {
        Component rendered = message.component(MessageKeys.COMMON_PLAYER_NOT_FOUND, "player", "Notch");

        Assertions.assertTrue(plain(rendered).contains("Notch"));
        Assertions.assertTrue(plain(rendered).contains("has never played"));
    }

    @Test
    @DisplayName("the shared prefix resolves from the placeholder palette, with its gradient")
    void prefixResolvesFromPalette() {
        Component rendered = message.component(MessageKeys.ERROR_NO_REGION);
        String text = plain(rendered);

        Assertions.assertTrue(text.contains("Realty"),
                "the prefix palette entry did not resolve: " + text);
        Assertions.assertTrue(text.contains("not standing in a WorldGuard region"));
    }

    @Test
    @DisplayName("a player-supplied value cannot inject markup")
    void plainValuesAreInert() {
        // The old renderer used Placeholder.unparsed for these; the framework escapes by default.
        // Either way a player named "<red>x" must not colour the message.
        Component rendered = message.component(MessageKeys.COMMON_PLAYER_NOT_FOUND,
                "player", "<red>evil</red>");

        Assertions.assertTrue(plain(rendered).contains("<red>evil</red>"),
                "markup in a caller value must render literally, not be interpreted");
    }

    @Test
    @DisplayName("a Component value keeps its own styling")
    void componentValuesKeepStyling() {
        Component styled = MiniMessage.miniMessage().deserialize("<green>STYLED</green>");
        Component rendered = message.component(MessageKeys.LIST_FOOTER,
                "page", "1", "total", "2", "previous", styled, "next", Component.empty());

        Assertions.assertTrue(plain(rendered).contains("STYLED"));
    }

    @Test
    @DisplayName("Message.rich carries trusted markup through")
    void richValuesCarryMarkup() {
        Component rendered = message.component(MessageKeys.COMMON_ERROR,
                "error", Message.rich("<green>ok</green>"));

        // The markup was interpreted rather than escaped, so the literal tag is gone.
        Assertions.assertFalse(plain(rendered).contains("<green>"),
                "rich markup should be parsed, not escaped");
        Assertions.assertTrue(plain(rendered).contains("ok"));
    }

    @Test
    @DisplayName("a multi-line message still renders as multiple lines")
    void multiLineMessagesKeepTheirLines() {
        Component rendered = message.component(MessageKeys.HELP_MAIN);

        Assertions.assertTrue(plain(rendered).contains("\n"),
                "help.main is a list of lines and must render as several");
    }

    @Test
    @DisplayName("format() fills a placeholder inside a click-tag argument")
    void formatFillsTagArguments() {
        // component() rewrites {name} into a generated MiniMessage tag, which cannot nest inside a
        // tag argument. The pagination links therefore go through format() and are deserialized by
        // hand — the same escape hatch the old renderer needed.
        String raw = message.format(MessageKeys.LIST_NEXT, "command", "/realty list --page 2");
        Assertions.assertTrue(raw.contains("<click:run_command:/realty list --page 2>"),
                "the click target was not substituted: " + raw);

        Component rendered = MiniMessage.miniMessage().deserialize(raw);
        ClickEvent click = rendered.clickEvent();
        Assertions.assertNotNull(click, "the rendered nav link has no click event");
        Assertions.assertEquals("/realty list --page 2", click.value());
    }

    @Test
    @DisplayName("format() resolves the prefix palette entry too")
    void formatResolvesPalette() {
        String raw = message.format(MessageKeys.ERROR_NO_REGION);

        Assertions.assertFalse(raw.contains("{prefix}"),
                "format() left the palette placeholder unresolved: " + raw);
    }

    @Test
    @DisplayName("every bundled key resolves to something other than the key itself")
    void everyKeyResolves() throws Exception {
        java.util.List<String> unresolved = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : MessageKeys.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            String key = (String) field.get(null);
            if (key.equals(message.format(key))) {
                unresolved.add(key);
            }
        }
        Assertions.assertTrue(unresolved.isEmpty(),
                "keys with no entry in messages.properties: " + unresolved);
    }

    /** Reads the bundled YAML so the two renderings can be compared key by key. */
    private static String bundledYamlText() throws IOException {
        try (InputStream stream = MessageRenderingEquivalenceTest.class
                .getResourceAsStream("/messages.yml");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (a, b) -> a + b + "\n");
        }
    }

    @Test
    @DisplayName("the bundled properties file covers every key the YAML defined")
    void propertiesCoversYaml() throws IOException {
        Assertions.assertFalse(bundledYamlText().isBlank());
        // Coverage itself is asserted key-by-key in MessagesYamlConverterTest against MessageKeys;
        // this only guards against the properties file being emptied or truncated.
        Assertions.assertTrue(
                Files.readString(dataFolder.resolve("messages.properties")).lines().count() > 400,
                "messages.properties looks truncated");
    }
}
