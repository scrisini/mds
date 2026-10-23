package com.mds.model;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.TemporalAmount;
import java.util.Arrays;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Calendar periods for OHLC candles, in the configured zone: hours start on the local hour, and the
 * rest at local midnight: days at 00:00, weeks on Monday (ISO-8601), months on the 1st, years on
 * 1 January. An hour is 60 minutes even when daylight saving repeats or skips a local hour.
 */
public enum CandlePeriod {
    HOUR(Duration.ofHours(48)),
    DAY(Period.ofDays(30)),
    WEEK(Period.ofWeeks(26)),
    MONTH(Period.ofMonths(24)),
    YEAR(Period.ofYears(10));

    private final TemporalAmount defaultLookback;

    CandlePeriod(TemporalAmount defaultLookback) {
        this.defaultLookback = defaultLookback;
    }

    /** Lower-case name used in the API and as the InfluxDB tag, e.g. {@code day}. */
    @JsonValue
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** How far back history requests reach when no {@code from} is given. */
    public TemporalAmount defaultLookback() {
        return defaultLookback;
    }

    /** Start of the period containing {@code instant}. */
    public Instant start(Instant instant, ZoneId zone) {
        ZonedDateTime local = instant.atZone(zone);
        ZonedDateTime start = switch (this) {
            case HOUR -> local.truncatedTo(ChronoUnit.HOURS);
            case DAY -> local.truncatedTo(ChronoUnit.DAYS);
            case WEEK -> local.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> local.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            case YEAR -> local.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        };
        return start.toInstant();
    }

    /** Exclusive end of the period starting at {@code start}; the next period's start. */
    public Instant end(Instant start, ZoneId zone) {
        ZonedDateTime from = start.atZone(zone);
        ZonedDateTime end = switch (this) {
            case HOUR -> from.plusHours(1); // on the instant time-line: 60 minutes across DST changes
            case DAY -> from.plusDays(1).truncatedTo(ChronoUnit.DAYS);
            case WEEK -> from.plusWeeks(1).truncatedTo(ChronoUnit.DAYS);
            case MONTH -> from.plusMonths(1).truncatedTo(ChronoUnit.DAYS);
            case YEAR -> from.plusYears(1).truncatedTo(ChronoUnit.DAYS);
        };
        return end.toInstant();
    }

    public static CandlePeriod parse(String value) {
        return Arrays.stream(values())
                .filter(p -> p.id().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid period '%s', expected one of hour, day, week, month, year".formatted(value)));
    }
}
