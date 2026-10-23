package com.mds.configdata;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.mds.cluster.Cluster;
import com.mds.config.InfluxProperties;
import com.mds.config.MdsProperties;
import com.mds.config.ProductionSafeguards;
import com.mds.logging.LogEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Answers "would this instance start again?" without restarting it. Every
 * {@code mds.config-check.interval}, and on demand ({@code /actuator/configcheck}), it reads
 * Parameter Store and Vault the way startup does, puts the fresh values in place of the ones read
 * at startup, and validates the result as startup would: mds's settings bind, production mode's
 * safeguards pass, and the settings every cluster member shares still match the running cluster.
 *
 * <p>A problem is reported in {@code /actuator/health} ({@code configCheck}), the
 * {@code mds.config.restartable} gauge and {@link LogEvent#CONFIG_CHECK_FAILED}, while the instance
 * keeps running on the configuration it started with. Readiness is unaffected, so a bad parameter
 * can't take every instance out of service at once. Keys that changed since startup are reported by
 * name ({@link LogEvent#CONFIG_CHANGED}); they take effect at the next restart. Values never appear.
 */
@Slf4j
@Component
public class ConfigCheck implements HealthIndicator {

    /**
     * One check's outcome.
     *
     * @param restartable whether an instance started now would start
     * @param problems    why it wouldn't, each naming the store or setting at fault
     * @param changed     keys added, removed or changed in Parameter Store or Vault since startup
     */
    public record Result(Instant checkedAt, boolean restartable, List<String> problems, List<String> changed) {
    }

    /** What a check found so far; filled in by its steps. */
    private record Findings(List<String> problems, List<String> changed) {

        Findings() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    private final ConfigurableEnvironment environment;
    private final MdsProperties running;
    private final Cluster cluster;
    private final Clock clock;
    private final AtomicInteger restartable = new AtomicInteger(1);
    private volatile Result last;
    private List<String> reportedChanges = List.of();

    public ConfigCheck(ConfigurableEnvironment environment, MdsProperties running, Cluster cluster,
                       MeterRegistry registry, Clock clock) {
        this.environment = environment;
        this.running = running;
        this.cluster = cluster;
        this.clock = clock;
        // this instance just started with this configuration, which is the same check
        this.last = new Result(clock.instant(), true, List.of(), List.of());
        Gauge.builder("mds.config.restartable", restartable, AtomicInteger::get)
                .description("1 while the stored configuration would let this instance start again, else 0")
                .register(registry);
    }

    @Scheduled(initialDelayString = "${mds.config-check.interval:5m}",
            fixedDelayString = "${mds.config-check.interval:5m}")
    void scheduled() {
        run();
    }

    /** Checks now, reports the outcome and keeps it as {@link #last()}. */
    public synchronized Result run() {
        Result result = check();
        report(result);
        last = result;
        restartable.set(result.restartable() ? 1 : 0);
        return result;
    }

    /** The latest outcome; at startup, the startup itself. */
    public Result last() {
        return last;
    }

    @Override
    public Health health() {
        Result result = last;
        return (result.restartable() ? Health.up() : Health.down())
                .withDetail("mode", running.mode().toString())
                .withDetail("checkedAt", result.checkedAt().toString())
                .withDetail("problems", result.problems())
                .withDetail("changedSinceStartup", result.changed())
                .build();
    }

    private Result check() {
        Binder current = Binder.get(environment);
        MutablePropertySources candidate = startupSources();
        Findings findings = new Findings();
        SsmSettings ssm = current.bindOrCreate("mds.ssm", SsmSettings.class);
        if (ssm.enabled()) {
            refresh(candidate, SsmConfigData.sourceName(ssm), "AWS SSM Parameter Store path " + ssm.path(),
                    () -> SsmConfigData.read(ssm), findings);
        }
        VaultSettings vault = current.bindOrCreate("mds.vault", VaultSettings.class);
        if (vault.enabled()) {
            refresh(candidate, VaultConfigData.sourceName(vault), "Vault secret " + vault.kvMount() + "/" + vault.path(),
                    () -> new VaultClient(vault).readSecret(), findings);
        }
        validate(candidate, findings);
        return new Result(clock.instant(), findings.problems().isEmpty(),
                List.copyOf(findings.problems()), List.copyOf(new TreeSet<>(findings.changed())));
    }

    /** A copy of the environment's sources, without Spring's cached view of the originals. */
    private MutablePropertySources startupSources() {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                sources.addLast(source);
            }
        }
        return sources;
    }

    /** Puts the store's current values in place of those read at startup, noting the keys that differ. */
    private static void refresh(MutablePropertySources candidate, String name, String store,
                                Supplier<Map<String, Object>> read, Findings findings) {
        Map<String, Object> fresh;
        try {
            fresh = read.get();
        } catch (RuntimeException e) {
            // the store's own messages name the path and status, never a token or a value
            findings.problems().add(store + ": " + e.getMessage());
            return;
        }
        PropertySource<?> atStartup = candidate.get(name);
        findings.changed().addAll(changedKeys(atStartup, fresh));
        if (atStartup != null) {
            candidate.replace(name, new MapPropertySource(name, fresh));
        }
    }

    /** Names of the keys added, removed or changed. */
    static Set<String> changedKeys(PropertySource<?> atStartup, Map<String, Object> fresh) {
        Set<String> changed = new HashSet<>();
        if (atStartup instanceof EnumerablePropertySource<?> before) {
            for (String key : before.getPropertyNames()) {
                if (!Objects.equals(String.valueOf(before.getProperty(key)), String.valueOf(fresh.get(key)))) {
                    changed.add(key);
                }
            }
            fresh.keySet().stream().filter(key -> !before.containsProperty(key)).forEach(changed::add);
        } else {
            changed.addAll(fresh.keySet());
        }
        return changed;
    }

    /** Binds mds's settings from the candidate sources as startup would, then applies the startup checks. */
    private void validate(MutablePropertySources candidate, Findings findings) {
        Binder binder = new Binder(ConfigurationPropertySources.from(candidate),
                new PropertySourcesPlaceholdersResolver(candidate));
        MdsProperties next;
        try {
            next = binder.bindOrCreate("mds", MdsProperties.class);
            binder.bindOrCreate("mds.influx", InfluxProperties.class);
            binder.bindOrCreate("mds.ssm", SsmSettings.class);
            binder.bindOrCreate("mds.vault", VaultSettings.class);
        } catch (BindException e) {
            findings.problems().add(describe(e));
            return;
        }
        findings.problems().addAll(ProductionSafeguards.violations(binder));
        sharedSettingsChange(next).ifPresent(findings.problems()::add);
    }

    /** Decimal precision and candle zone must match the running cluster, or a restarted member is refused. */
    private Optional<String> sharedSettingsChange(MdsProperties next) {
        String now = Cluster.sharedSettings(running);
        String then = Cluster.sharedSettings(next);
        if (now.equals(then) || cluster.memberCount() < 2) {
            return Optional.empty();
        }
        return Optional.of(("mds.decimal or mds.ohlc.zone would change from %s to %s: the running cluster would "
                + "refuse an instance restarted now (MDS-1009); stop every instance to change them").formatted(now, then));
    }

    /** The setting and the reason, e.g. {@code mds.decimal: mds.decimal.scale must be between 0 and 18, was 99}. */
    private static String describe(BindException e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return e.getName() + ": " + cause.getMessage();
    }

    private void report(Result result) {
        if (!result.restartable()) {
            LogEvent.CONFIG_CHECK_FAILED.at(log).with("problems", result.problems()).log();
        } else if (!last.restartable()) {
            LogEvent.CONFIG_CHECK_PASSED.at(log).log();
        }
        if (!result.changed().isEmpty() && !result.changed().equals(reportedChanges)) {
            LogEvent.CONFIG_CHANGED.at(log).with("keys", result.changed()).log();
        }
        reportedChanges = result.changed();
    }
}
