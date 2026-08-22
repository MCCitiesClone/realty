package io.github.md5sha256.realty.settings;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.UUID;

@ConfigurationComponent(file = "settings.yml")
public record Settings(
        @ConfigurationValue(path = "default-freehold-authority-uuid") @NotNull UUID defaultFreeholdAuthority,
        @ConfigurationValue(path = "default-freehold-titleholder-uuid") @Nullable UUID defaultFreeholdTitleholder,
        @ConfigurationValue(path = "default-leasehold-authority-uuid") @NotNull UUID defaultLeaseholdAuthority,
        @ConfigurationValue(path = "date-format") @NotNull String dateFormatPattern,
        @ConfigurationValue(path = "profile-reapply-per-tick") int profileReapplyPerTick,
        @ConfigurationValue(path = "subregion-min-volume") int subregionMinVolume,
        @ConfigurationValue(path = "offer-payment-duration-seconds") long offerPaymentDurationSeconds,
        @ConfigurationValue(path = "lease-termination-notice-seconds") long terminationNoticeSeconds,
        @ConfigurationValue(path = "subregion-tag-blacklist") @NotNull List<String> subregionTagBlacklist,
        @ConfigurationValue(path = "subregion-wand-material") @NotNull String subregionWandMaterial,
        @ConfigurationValue(path = "teleportation-starting-height") int teleportStartHeight
) {

    /** Fallback matching the shipped settings.yml, used when the pattern is unset or unparseable. */
    public static final String DEFAULT_DATE_FORMAT = "EEE, d MMMM yyyy (HH:mm)";

    public Settings {
        if (dateFormatPattern == null || dateFormatPattern.isBlank()) {
            dateFormatPattern = DEFAULT_DATE_FORMAT;
        }
        if (profileReapplyPerTick <= 0) {
            profileReapplyPerTick = 10;
        }
        if (subregionMinVolume <= 0) {
            subregionMinVolume = 20;
        }
        if (offerPaymentDurationSeconds <= 0) {
            offerPaymentDurationSeconds = 86400;
        }
        if (terminationNoticeSeconds <= 0) {
            terminationNoticeSeconds = 604800;
        }
        if (subregionTagBlacklist == null) {
            subregionTagBlacklist = List.of();
        }
        if (subregionWandMaterial == null || subregionWandMaterial.isBlank()) {
            subregionWandMaterial = "GOLDEN_AXE";
        }
    }

    /**
     * A formatter for {@code date-format}.
     *
     * <p>Built per call on purpose: {@link SimpleDateFormat} is not thread-safe, and Realty
     * formats dates from command handlers that complete on database threads as well as from the
     * main thread. A single shared instance would be a data race.</p>
     *
     * <p>An unparseable pattern falls back to {@link #DEFAULT_DATE_FORMAT} rather than throwing
     * from deep inside a command.</p>
     */
    public @NotNull SimpleDateFormat dateFormat() {
        try {
            return new SimpleDateFormat(this.dateFormatPattern);
        } catch (IllegalArgumentException invalidPattern) {
            return new SimpleDateFormat(DEFAULT_DATE_FORMAT);
        }
    }
}

