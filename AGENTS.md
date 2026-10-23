# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. See `README.md` for
the user-facing API, configuration and InfluxDB schema.

## Project

`mds` is a Spring Boot service that streams crypto tickers over WebSocket from several exchanges
(XChange `xchange-stream-*`), aggregates them per pair, serves REST, Server-Sent Events and a client WebSocket, builds
OHLC candles, and archives everything in InfluxDB 2.x. Several instances form an embedded Hazelcast
cluster that shares the candles and balances real-time subscribers.

- Java 21 (Gradle toolchain), Spring Boot 4.1, Gradle 9 via the wrapper, Lombok
- Jackson 3 (`tools.jackson.*`); annotations still come from `com.fasterxml.jackson.annotation`
- RxJava 3 for exchange streams and SSE throttling; Micrometer for metrics
- Hazelcast 5.5 (embedded, version from the Spring Boot BOM) for the cluster

## Commands

```bash
./gradlew test                 # all tests (offline, no InfluxDB needed); must pass before committing
./gradlew compileJava          # fast compile check
scripts/dev.sh check           # verify local prerequisites (Java, Docker, ports, exchange reachability)
scripts/dev.sh run [args...]   # start InfluxDB, then the app on :8080; args go to Spring Boot
scripts/dev.sh status          # InfluxDB and app health
scripts/dev.sh hooks           # install the commit-msg hook (do this once per clone)
scripts/test-commit-msg.sh     # test the hook after changing it or .commitlintrc.json
scripts/check-secrets.sh --all # scan tracked files for plaintext secrets (the hook scans staged files)
scripts/test-check-secrets.sh  # test the secrets check after changing it
```

See README "Running locally" for prerequisites and all `scripts/dev.sh` commands. Never run
`scripts/dev.sh reset` unless asked: it deletes the local InfluxDB data.

## Layout

```
com.mds
├── cluster/      Hazelcast member config, Cluster (pair ownership, shared maps), cluster health
├── config/       @ConfigurationProperties records (MdsProperties, InfluxProperties), beans, lifecycle logging
├── configdata/   startup loaders for AWS SSM Parameter Store and Vault (spring.factories)
├── logging/      LogEvent catalogue, EventLog, LogFields, RequestIds: logs for production support
├── exchange/     SupportedExchange, MarketStreamManager (WebSocket lifecycle), Bitstamp patch
├── math/         Decimal fixed-point type and its JSON serializer
├── model/        immutable records: MarketPair, PriceQuote, AggregatedPrice, Candle, …
├── service/      QuoteBook, PriceService, PriceAggregator, PriceArchiver, PriceStreamService, StreamRebalancer,
│                 CandleService and its cluster types (CandleKey, ApplyPrice, RollForward, CandleSerializers)
├── persistence/  PriceRepository (all InfluxDB reads/writes), InfluxHealthIndicator
└── web/          REST/SSE controllers, PriceWebSocketHandler, ApiExceptionHandler (RFC 9457 problem details), RequestIdFilter
```

## Conventions

- **Logging:** Lombok `@Slf4j` only; never declare `LoggerFactory` loggers by hand. Logs are for
  production support and log processors (ELK, Splunk, New Relic):
  - **Every operational log line is a catalogued event:**
    `LogEvent.X.at(log).with("exchange", id).cause(e).log()`. Don't call `log.info/warn/error`
    directly; `log.debug` is fine for developer diagnostics. A new situation gets a new `LogEvent`
    constant with a stable code (`MDS-nnnn`, never reused or renumbered), a fixed level and a
    summary saying what happened, what it means for callers and what happens next, plus a section
    in `docs/runbook.md` with the same anchor and level. `LogEventCatalogueTest` fails the build if
    either is missing or they disagree.
  - Codes: `1xxx` lifecycle, `2xxx` requests and business outcomes, `30xx` exchanges, `31xx`
    InfluxDB, `32xx` the cluster, `9xxx` unexpected failures.
  - Levels: INFO for lifecycle and business outcomes, WARN for problems the service recovers from
    or that callers retry, ERROR for things a person must act on (and its summary says what happens
    next, after a `;`). Alert rules key on codes, so keep a code's level stable.
  - Details are `mds.*` fields (snake_case: `exchange`, `pair`, `retry_in`, `reason`) holding
    identifiers and reasons: never whole requests, personal data, secrets or tokens. Use
    `.with("reason", e.getMessage())` for retried failures and `.cause(e)` (stack trace) for errors.
  - Field names follow the Elastic Common Schema (`event.code`, `event.reference`,
    `http.request.id`, `error.*`); constants are in `LogFields`.
  - Production logs are ECS JSON from Spring Boot's structured logging (`MDS_LOG_FORMAT=ecs`), one
    object per line. Nothing else may write to stdout in that mode; `StructuredLoggingTest` checks.
  - Every request gets an id (`RequestIdFilter`): returned in `X-Request-Id`, and as `requestId` in
    every problem body (`ApiExceptionHandler`). Work handed to other threads must carry it:
    `try (RequestIds.Scope s = RequestIds.bind(requestId)) { ... }`.
- **Numbers:** prices and volumes are `com.mds.math.Decimal`, never `BigDecimal` or `double`.
  `BigDecimal` is allowed only at the XChange boundary (`QuoteBook`) and inside `Decimal`;
  `double` only when writing to or reading from InfluxDB. Precision is set by `mds.decimal.*`
  once per JVM, so don't create non-zero `Decimal`s in static initialisers or bean constructors.
- **Configuration:** add settings as fields on the `MdsProperties` records with `@DefaultValue`,
  then document them in `application.yml` and the README configuration table. Deployed values come
  from AWS SSM Parameter Store, secrets from Vault (README "Configuration and secrets"); both are
  read at startup by `configdata/` and fail closed.
- **Secrets:** never in plaintext in git, logs or error messages: not in `application.yml`, tests,
  fixtures or docs.
  - A new secret is a property that Vault supplies when deployed, with only a local development
    default (like `mds-dev-token`).
  - Secrets that must be in the repository are SOPS-encrypted under `config/secrets/`.
  - `scripts/check-secrets.sh` (the pre-commit hook and the `secrets` workflow) enforces this; run
    `scripts/test-check-secrets.sh` after changing it.
  - Never `git commit --no-verify` to get past it; if it's a false alarm, say so and ask.
- **Time:** inject `java.time.Clock` (a bean); never call `Instant.now()` in services or
  controllers, so tests can control time (`com.mds.support.MutableClock`).
- **InfluxDB:** all access goes through `PriceRepository`. Any value interpolated into Flux must be
  validated (`MarketPair` codes, regex-checked ids, enums, `Instant`s); never pass raw user input.
- **Errors:** throw domain exceptions from services and map them in `ApiExceptionHandler`, which
  adds the `requestId`. Anything unmapped becomes a 500 logged as `MDS-9001`.
- **Metrics:** name custom meters `mds.<area>.<thing>`, and keep each meter's tag keys consistent.
- **Design:** follow `docs/design-principles.md`, and record decisions in README "Design":
  - A new type needs a reason: an invariant, a lifecycle, a boundary or a domain concept.
    Interfaces only at real seams; compound keys are private nested records.
  - Each method has one purpose; a coordinator only calls its steps.
  - On the hot paths (README "Performance: JIT and GC"), keep methods small and call sites
    monomorphic. Use loops rather than streams, no exceptions for routine outcomes (return
    `Optional`), and build constants and indexes once.
  - Bound anything that grows with traffic. Claim a speed-up only with a measurement: a `perf`
    commit carries the numbers.
- **Style:** match the surrounding code: constructor injection, records for data, Javadoc on
  public types and non-obvious methods, imports grouped `java.*`, then others, no wildcards.

## Tests

- JUnit 5 + AssertJ + Mockito; `@WebMvcTest` with `@MockitoBean` for controllers.
- Unit-test services with real collaborators where cheap (`QuoteBook`, `PriceAggregator`) and mock
  I/O (`PriceRepository`, `MarketStreamManager`, XChange exchanges).
- RxJava timing: use `TestScheduler`; async waits: Awaitility, never `Thread.sleep` in assertions.
- `Decimal` precision is JVM-global; tests that configure it must call `Decimal.resetForTests()`.
- Add or update tests with every behaviour change.

## Gotchas

- XChange 6 deprecates `getTicker(CurrencyPair)`, but most exchange modules only implement that
  overload; keep using it (with `@SuppressWarnings("deprecation")`) and don't switch to `Instrument`.
- `PatchedBitstampStreamingExchange` works around XChange 6.0.0 dropping every Bitstamp message.
  `PatchedBitstampStreamingExchangeTest` fails once upstream is fixed; then remove the patch.
- Kraken and Bitstamp streaming exchanges publish no instrument metadata; all pairs are subscribed.
- **Clustering** (README "Running several instances"): every instance is a Hazelcast member, and
  all instances sharing an InfluxDB bucket must form one cluster, or their candles overwrite each
  other.
  - Each pair is owned by one member (`Cluster.owns`), which alone builds its candles and archives
    its prices. Anything new that writes per pair must check ownership too.
  - Candle state is in the cluster maps (`Cluster.CANDLES`, ...), changed only through entry
    processors (`ApplyPrice`, `RollForward`); keep the candle logic in pure `Candle` methods they
    call.
  - Every type stored in or sent through the cluster needs a compact serializer in
    `CandleSerializers`, never Java serialization. Changing a format needs a full stop of every
    instance, so say so in the commit.
  - Hazelcast settings that reach outside the network stay off (`ClusterConfigTest`): phone-home,
    auto-detection, multicast. Its JVM shutdown hook stays off, so Spring flushes candles first.
  - Tests that start members use `TestClusters` (unique names, ports from 5901). Wait for
    `isClusterSafe()` before relying on backups.
- Real-time clients (SSE and the `/api/v1/ws/prices` WebSocket) both go through
  `PriceStreamService.open`, which owns rates (`StreamRate`), the subscriber limit, metrics,
  heartbeats and slow-client handling. Add new transports there rather than re-implementing throttling.
  `WebSocketSession` isn't thread-safe: send only through the `ConcurrentWebSocketSessionDecorator`
  in `PriceWebSocketHandler`.
- **Outgoing traffic must honour `mds.socks-proxy`** (a security requirement where it's used).
  Exchange connections get it from `MarketStreamManager.configure`; never add an HTTP client or
  socket to an external host that bypasses it. `SocksProxyTest` runs every `SupportedExchange`
  against a fake SOCKS5 server: a new exchange must pass it, and an XChange upgrade that breaks
  it must not be merged. Don't delete `src/main/resources/rescu.properties` (it makes exchange
  REST calls use SOCKS; startup fails closed without it).
- `PriceStreamService`, `MarketStreamManager` and `CandleService` are `SmartLifecycle` beans
  (phases in that order, highest first). `CandleService` starts first and stops last, so it
  recovers before the first tick and flushes after the last; `PriceStreamService` stops first and
  closes every stream, so clients reconnect elsewhere before the exchanges disconnect.
- **Deployment** (`docs/deployment.md`, files in `deploy/`): ECS or EKS behind an ALB. Its timeouts
  must agree with `application.yml` (ALB idle 120 s < `server.tomcat.keep-alive-timeout` 130 s);
  change both together. `deploy/` holds examples with `CHANGE` placeholders, never real account
  ids, hosts or secrets.

## Working agreements

- When a request is genuinely ambiguous (which library is meant, what a feature should do), ask.
  Otherwise choose a sensible default and say which one you chose.
- Verify before claiming: build, run the tests, run the app where it matters, and report failures
  as failures.
- One branch per change, cut from `develop`. Never commit directly to `main` or `develop`.
- Commit, push, open pull requests, merge and delete branches only when asked to.
- Stage explicit paths. Never commit IDE files (`.idea/`, files the IDE staged) or edits you didn't
  make; mention them instead.
- Never `git commit --no-verify`. If the hook rejects a message, fix the message.
- Don't change repository settings, rulesets or remote branches unless asked. Scripts that do
  default to a dry run.
- A developer may already be running `MdsApplication` from the IDE against the same InfluxDB.
  Check with `pgrep -fl com.mds.MdsApplication` first. Start your copy with a marker,
  `scripts/dev.sh run --mds.instance=agent`, and stop only that one:
  `pkill -f 'mds.instance=agent'`. Never kill processes by class name.

## Commits and releases

Commits follow [Conventional Commits](https://www.conventionalcommits.org/), enforced locally by
`.githooks/commit-msg` (install with `scripts/dev.sh hooks`) and on pull requests by the
`commitlint` workflow, both using `.commitlintrc.json`. semantic-release (`.releaserc.json`) reads
them to pick the next version; the `release` workflow (`.github/workflows/release.yml`) runs it on
every push to `main`, after the tests pass, and publishes the tag and GitHub release. Never use
`git commit --no-verify`; if the hook rejects a message, fix the message. Full guide: README
"Contributing".

```
<type>(<optional scope>): <imperative summary, lower case, no period>

<body: what and why, wrapped at 100>

Refs: #123
Co-Authored-By: Name <email>
BREAKING-CHANGE: <what breaks and how to migrate>
```

| Type | Use for | Release |
|---|---|---|
| `feat` | new behaviour visible to API users or operators | minor |
| `fix` | bug fix | patch |
| `perf` | performance improvement | patch |
| `refactor` | restructuring without behaviour change | patch |
| `revert` | reverting a previous commit | patch |
| `docs`, `test`, `build`, `ci`, `chore`, `style` | everything else | none |

A breaking-change footer, or `!` after the type (`feat!:`), triggers a major release. Use it
for any incompatible change to the REST/SSE/WebSocket API, configuration keys, log event codes or
InfluxDB schema.

- Spell the footer `BREAKING-CHANGE: ` or `BREAKING CHANGE: ` (upper case, singular).
  semantic-release matches those keywords in any case, with or without a colon, at the start of
  any body line, and misses the plural; the hook rejects every other line that starts with
  "breaking change", so reword such prose.
- **The breaking-change footer goes last.** semantic-release takes everything from it to the end
  of the message as the release note, so any line after it (`Co-Authored-By`, `Refs`) ends up in
  the published notes. With trailers, write the hyphenated `BREAKING-CHANGE:` as the **last line
  of the trailer block**, directly after them with no blank line (indent continuation lines). The
  spaced form isn't a valid Git trailer and would make GitHub drop the co-author credit.
- **Write `#N` only for a real reference.** Any `#4` becomes "closes #4" in the release notes, and
  the release bot comments on that issue or PR. To mention a pull request in passing, write "PR 4".

Suggested scopes: `streaming`, `prices`, `candles`, `decimal`, `metrics`, `persistence`, `api`,
`config`, `deps`.

Examples:

```
feat(candles): carry forward the last close through periods without updates
fix(streaming): resubscribe when an exchange stream completes
feat(api)!: rename POST /prices/collect to /prices/archive
```

Branching (README "Branching and merging"): branch from `develop`, open a pull request into
`develop` and merge it with a **merge commit** (never squash or rebase into `develop`). Releases
go `develop` → `main` by **squash** merge, with a PR title that is a valid Conventional Commit
carrying the highest bump of the included changes, and a squash body made of the messages of the
commits that are **new** in this release (GitHub's default also lists already-released commits,
whose footers and `#N` references would land in the notes again); afterwards back-merge `main`
into `develop` with a merge commit. The first squash into `main` with `release.yml` in place
publishes **v1.0.0**: dry-run it first (`npx semantic-release --dry-run --no-ci` on a clone of
`main` with the two packages pinned in `release.yml`). Never push directly to `main` or `develop`. If `develop` doesn't exist yet,
branch from `main` and target `main`.
