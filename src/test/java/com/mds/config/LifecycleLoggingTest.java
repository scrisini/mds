package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;

import com.mds.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LifecycleLoggingTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LifecycleLogging logging = new LifecycleLogging(mock(MdsProperties.class), clock);

    @Test
    void logsTheStartAndEndOfAShutdownWithItsDuration(CapturedOutput output) {
        logging.shutdownStarted();
        clock.advance(Duration.ofSeconds(12));
        logging.shutdownComplete();

        assertThat(output).contains("MDS-1020").contains("MDS-1021").contains("PT12S");
    }

    @Test
    void reportsNoEndOfAShutdownThatNeverStarted(CapturedOutput output) {
        logging.shutdownComplete();

        assertThat(output).doesNotContain("MDS-1021");
    }
}
