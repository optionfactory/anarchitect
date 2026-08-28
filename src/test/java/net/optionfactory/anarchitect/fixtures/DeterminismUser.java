package net.optionfactory.anarchitect.fixtures;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

public class DeterminismUser {

    public String noLocale(String s) {
        return s.toUpperCase();
    }

    public String withLocale(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    public LocalDate noZone() {
        return LocalDate.now();
    }

    public LocalDate withZone(ZoneId zone) {
        return LocalDate.now(zone);
    }

    public Instant instant() {
        return Instant.now();
    }
}
