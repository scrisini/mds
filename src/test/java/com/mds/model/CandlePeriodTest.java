package com.mds.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class CandlePeriodTest {

    // 2026-10-21 is a Wednesday
    private static final Instant NOW = Instant.parse("2026-10-21T15:42:07Z");

    @ParameterizedTest
    @CsvSource({
            "HOUR,  2026-10-21T15:00:00Z, 2026-10-21T16:00:00Z",
            "DAY,   2026-10-21T00:00:00Z, 2026-10-22T00:00:00Z",
            "WEEK,  2026-10-19T00:00:00Z, 2026-10-26T00:00:00Z",
            "MONTH, 2026-10-01T00:00:00Z, 2026-11-01T00:00:00Z",
            "YEAR,  2026-01-01T00:00:00Z, 2027-01-01T00:00:00Z"})
    void utcBoundaries(CandlePeriod period, Instant start, Instant end) {
        assertThat(period.start(NOW, ZoneOffset.UTC)).isEqualTo(start);
        assertThat(period.end(start, ZoneOffset.UTC)).isEqualTo(end);
        assertThat(period.start(end, ZoneOffset.UTC)).as("end starts the next period").isEqualTo(end);
        assertThat(period.start(end.minusNanos(1), ZoneOffset.UTC)).isEqualTo(start);
    }

    @Test
    void followsLocalMidnightAcrossDaylightSavingChanges() {
        ZoneId sydney = ZoneId.of("Australia/Sydney");
        // DST starts 2026-10-04 in Sydney: that day is 23 hours long (+10:00 -> +11:00)
        Instant dayStart = CandlePeriod.DAY.start(Instant.parse("2026-10-04T05:00:00Z"), sydney);

        assertThat(dayStart).isEqualTo(Instant.parse("2026-10-03T14:00:00Z"));
        assertThat(CandlePeriod.DAY.end(dayStart, sydney)).isEqualTo(Instant.parse("2026-10-04T13:00:00Z"));
        assertThat(CandlePeriod.MONTH.start(NOW, sydney)).isEqualTo(Instant.parse("2026-09-30T14:00:00Z"));
    }

    @Test
    void hoursLastSixtyMinutesWhenDaylightSavingRepeatsAnHour() {
        ZoneId sydney = ZoneId.of("Australia/Sydney");
        // DST ends 2026-04-05 in Sydney: 02:00-03:00 local happens twice (+11:00, then +10:00)
        Instant firstTwoAm = Instant.parse("2026-04-04T15:00:00Z");
        Instant secondTwoAm = Instant.parse("2026-04-04T16:00:00Z");

        assertThat(CandlePeriod.HOUR.start(firstTwoAm.plusSeconds(1800), sydney)).isEqualTo(firstTwoAm);
        assertThat(CandlePeriod.HOUR.end(firstTwoAm, sydney)).isEqualTo(secondTwoAm);
        assertThat(CandlePeriod.HOUR.start(secondTwoAm.plusSeconds(1800), sydney)).isEqualTo(secondTwoAm);
        assertThat(CandlePeriod.HOUR.end(secondTwoAm, sydney)).isEqualTo(Instant.parse("2026-04-04T17:00:00Z"));
    }

    @Test
    void hoursStartOnTheLocalHourInZonesWithHalfHourOffsets() {
        ZoneId kolkata = ZoneId.of("Asia/Kolkata"); // +05:30

        assertThat(CandlePeriod.HOUR.start(NOW, kolkata)).isEqualTo(Instant.parse("2026-10-21T15:30:00Z"));
        assertThat(CandlePeriod.HOUR.end(Instant.parse("2026-10-21T15:30:00Z"), kolkata))
                .isEqualTo(Instant.parse("2026-10-21T16:30:00Z"));
    }

    @Test
    void parsesIdsCaseInsensitively() {
        assertThat(CandlePeriod.parse("Week")).isEqualTo(CandlePeriod.WEEK);
        assertThat(CandlePeriod.parse("HOUR")).isEqualTo(CandlePeriod.HOUR);
        assertThat(CandlePeriod.MONTH.id()).isEqualTo("month");
        assertThatIllegalArgumentException().isThrownBy(() -> CandlePeriod.parse("minute"));
    }
}
