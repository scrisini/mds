package com.mds.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** What closing the context does to running work, with the settings in {@code application.yml}. */
class GracefulShutdownTest {

    private final ApplicationContextRunner scheduling = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(JobConfig.class);

    @Test
    void waitsForARunningScheduledJob() {
        scheduling.run(context -> {
            Job job = context.getBean(Job.class);
            job.workFor = Duration.ofMillis(500);
            await().until(() -> job.started.getCount() == 0);

            context.close();

            assertThat(job.finished.getCount()).as("job finished before the context closed").isZero();
        });
    }

    @Test
    void interruptsAScheduledJobThatOutlastsTheLimit() {
        scheduling.withPropertyValues("spring.task.scheduling.shutdown.await-termination-period=100ms").run(context -> {
            Job job = context.getBean(Job.class);
            job.workFor = Duration.ofMinutes(5);
            await().until(() -> job.started.getCount() == 0);

            context.close();

            assertThat(job.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        });
    }

    @Test
    void aStuckStreamSendDoesNotHoldUpTheShutdown() {
        new ApplicationContextRunner().withUserConfiguration(StreamConfig.class).run(context -> {
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            context.getBean(ExecutorService.class).execute(() -> {
                started.countDown();
                sleep(Duration.ofMinutes(5), interrupted);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            context.close();

            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        });
    }

    private static void sleep(Duration duration, CountDownLatch interrupted) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            interrupted.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class JobConfig {

        @Bean
        Job job() {
            return new Job();
        }
    }

    /** Fixed rate, like {@code PriceArchiver}: those jobs are the ones Spring doesn't wait for by default. */
    static class Job {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        volatile Duration workFor;

        @Scheduled(initialDelay = 1, fixedRate = 3_600_000)
        void run() {
            await().until(() -> workFor != null);
            started.countDown();
            sleep(workFor, interrupted);
            if (interrupted.getCount() > 0) {
                finished.countDown();
            }
        }
    }
}
