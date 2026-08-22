package io.github.md5sha256.realty.command.resolver;

import io.github.md5sha256.realty.command.util.GroupPrefix;
import io.github.md5sha256.realty.database.entity.GovernmentPartyEntity;
import io.github.md5sha256.realty.party.PartyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Command arguments have to be typeable.
 *
 * <p>The commander maps these arguments onto {@code StringArgumentType.word()}, whose reader
 * accepts only
 * {@code 0-9 A-Z a-z _ - . +} in an unquoted argument. Anything else ends the token early and the
 * <em>client</em> refuses the command — "Expected whitespace to end one argument, but found
 * trailing data" — before the server ever sees it. So a parser that answers to a syntax containing
 * a colon is unreachable in play no matter how correct its logic is, which is how {@code gov:Name}
 * shipped: it parses perfectly in a unit test and cannot be typed in game.
 */
class BrigadierArgumentSyntaxTest {

    /** {@code com.mojang.brigadier.StringReader.isAllowedInUnquotedString}. */
    private static boolean typeableInACommand(String token) {
        return token.chars().allMatch(c ->
                (c >= '0' && c <= '9')
                        || (c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z')
                        || c == '_' || c == '-' || c == '.' || c == '+');
    }

    @Test
    @DisplayName("the government prefix is typeable")
    void governmentPrefixIsTypeable() {
        Assertions.assertTrue(typeableInACommand(AuthorityNames.GOVERNMENT_PREFIX),
                "'" + AuthorityNames.GOVERNMENT_PREFIX + "' cannot be typed as a command argument;"
                        + " the client rejects the command before sending it");
    }

    @Test
    @DisplayName("a government's suggested name is typeable")
    void suggestedGovernmentNamesAreTypeable() {
        // Whitespace is already squashed out for the same reason: an argument is one token.
        GovernmentPartyEntity spaced = new GovernmentPartyEntity(UUID.randomUUID(), 1, "Gov Develop");
        String suggested = PartyService.commandName(spaced);

        Assertions.assertEquals("GovDevelop", suggested);
        Assertions.assertTrue(typeableInACommand(suggested), suggested);
        Assertions.assertTrue(typeableInACommand(AuthorityNames.GOVERNMENT_PREFIX + suggested));
    }

    @Test
    @DisplayName("the original colon syntax is still recognised, for console and scripts")
    void legacyPrefixStillResolves() {
        // Server-side parsing never saw the colon problem, so anything already written against the
        // old syntax keeps working even though it is no longer suggested.
        Assertions.assertEquals("GovDevelop",
                AuthorityNames.governmentNameIfPrefixed("gov:GovDevelop"));
        Assertions.assertEquals("GovDevelop",
                AuthorityNames.governmentNameIfPrefixed("gov.GovDevelop"));
        Assertions.assertEquals("GovDevelop",
                AuthorityNames.governmentNameIfPrefixed("GOV.GovDevelop"));
    }

    @Test
    @DisplayName("a bare name is not treated as an explicit government reference")
    void bareNameIsNotForced() {
        // It still resolves to a government, but only after no such player is found.
        Assertions.assertNull(AuthorityNames.governmentNameIfPrefixed("GovDevelop"));
        Assertions.assertNull(AuthorityNames.governmentNameIfPrefixed("Notch"));
    }

    @Test
    @DisplayName("the WorldGuard group prefix is typeable")
    void groupPrefixIsTypeable() {
        Assertions.assertTrue(typeableInACommand(GroupPrefix.GROUP_PREFIX),
                "'" + GroupPrefix.GROUP_PREFIX + "' cannot be typed as a command argument");
    }

    @Test
    @DisplayName("both group spellings resolve, and a player name resolves to neither")
    void groupPrefixResolves() {
        // WorldGuard's own g: spelling still works from console, where the colon never mattered.
        Assertions.assertEquals("builders", GroupPrefix.groupNameIfPrefixed("g:builders"));
        Assertions.assertEquals("builders", GroupPrefix.groupNameIfPrefixed("g.builders"));
        Assertions.assertNull(GroupPrefix.groupNameIfPrefixed("Notch"));
    }
}
