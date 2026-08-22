package io.github.md5sha256.realty.command;

/** Lease-duration units offered in the subregion create dialog. */
public enum DurationUnit {
    MINUTES(60L, "Minutes"),
    HOURS(3600L, "Hours"),
    DAYS(86400L, "Days"),
    WEEKS(604800L, "Weeks");

    public final long seconds;
    public final String label;

    DurationUnit(long seconds, String label) {
        this.seconds = seconds;
        this.label = label;
    }
}
