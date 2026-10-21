package com.mds.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Calendar periods for OHLC candles. Boundaries are local midnight in the configured zone:
 * days start at 00:00, weeks on Monday (ISO-8601), months on the 1st, years on 1 January.
 */
public enum CandlePeriod {
    DAY(Period.ofDays(30)),
    WEEK(Period.ofWeeks(26)),
    MONTH(Period.ofMonths(24)),
    YEAR(Period.ofYears(10));

    private final Period defaultLookback;

    CandlePeriod(Period defaultLookback) {
        this.defaultLookback = defaultLookback;
    }

    /** Lower-case name used in the API and as the InfluxDB tag, e.g. {@code day}. */
    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** How far back history requests reach when no {@code from} is given. */
    public Period defaultLookback() {
        return defaultLookback;
    }

    /** Start of the period containing {@code instant}. */
    public Instant start(Instant instant, ZoneId zone) {
        ZonedDateTime day = instant.atZone(zone).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime start = switch (this) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
            case YEAR -> day.withDayOfYear(1);
        };
        return start.toInstant();
    }

    /** Exclusive end of the period starting at {@code start}; the next period's start. */
    public Instant end(Instant start, ZoneId zone) {
        ZonedDateTime from = start.atZone(zone);
        ZonedDateTime end = switch (this) {
            case DAY -> from.plusDays(1);
            case WEEK -> from.plusWeeks(1);
            case MONTH -> from.plusMonths(1);
            case YEAR -> from.plusYears(1);
        };
        return end.truncatedTo(ChronoUnit.DAYS).toInstant();
    }

    public static CandlePeriod parse(String value) {
        return Arrays.stream(values())
                .filter(p -> p.id().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid period '%s', expected one of day, week, month, year".formatted(value)));
    }
}
