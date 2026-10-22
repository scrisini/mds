package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mds.logging.LogEvent;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RxJavaErrorConfigTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RxJavaErrorConfig.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void capture() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void aClientThatWentAwayIsOnlyADebugLine() {
        RxJavaErrorConfig.undeliverable(new UndeliverableException(new IOException("Broken pipe")));

        assertThat(appender.list).singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    void anythingElseIsACodedError() {
        RxJavaErrorConfig.undeliverable(new UndeliverableException(new IllegalStateException("bug")));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).startsWith("[" + LogEvent.UNDELIVERABLE_ERROR.code() + "]");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }
}
