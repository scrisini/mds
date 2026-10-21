# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. See `README.md` for
the user-facing API, configuration and InfluxDB schema.

## Project

`mds` is a Spring Boot service that streams crypto tickers over WebSocket from several exchanges
(XChange `xchange-stream-*`), aggregates them per pair, serves REST and Server-Sent Events, builds
OHLC candles, and archives everything in InfluxDB 2.x.

- Java 21 (Gradle toolchain), Spring Boot 4.1, Gradle 9 via the wrapper, Lombok
- Jackson 3 (`tools.jackson.*`); annotations still come from `com.fasterxml.jackson.annotation`
- RxJava 3 for exchange streams and SSE throttling; Micrometer for metrics

## Commands

```bash
./gradlew test                 # all tests; must pass before committing
./gradlew compileJava          # fast compile check
docker compose up -d           # InfluxDB on :8086 (org mds, bucket prices, token mds-dev-token)
./gradlew bootRun              # app on :8080; needs InfluxDB and network access to exchanges
```

## Layout

```
com.mds
├── config/       @ConfigurationProperties records (MdsProperties, InfluxProperties), beans
├── exchange/     SupportedExchange, MarketStreamManager (WebSocket lifecycle), Bitstamp patch
├── math/         Decimal fixed-point type and its JSON serializer
├── model/        immutable records: MarketPair, PriceQuote, AggregatedPrice, Candle, …
├── service/      QuoteBook, PriceService, PriceAggregator, PriceArchiver, PriceStreamService, CandleService
├── persistence/  PriceRepository (all InfluxDB reads/writes), InfluxHealthIndicator
└── web/          REST/SSE controllers, ApiExceptionHandler (RFC 9457 problem details)
```

## Conventions

- **Logging:** Lombok `@Slf4j` only; never declare `LoggerFactory` loggers by hand.
- **Numbers:** prices and volumes are `com.mds.math.Decimal`, never `BigDecimal` or `double`.
  `BigDecimal` is allowed only at the XChange boundary (`QuoteBook`) and inside `Decimal`;
  `double` only when writing to or reading from InfluxDB. Precision is set by `mds.decimal.*`
  once per JVM, so don't create non-zero `Decimal`s in static initialisers or bean constructors.
- **Configuration:** add settings as fields on the `MdsProperties` records with `@DefaultValue`,
  then document them in `application.yml` and the README configuration table.
- **Time:** inject `java.time.Clock` (a bean); never call `Instant.now()` in services, so tests can
  control time (`com.mds.support.MutableClock`).
- **InfluxDB:** all access goes through `PriceRepository`. Any value interpolated into Flux must be
  validated (`MarketPair` codes, regex-checked ids, enums, `Instant`s); never pass raw user input.
- **Errors:** throw domain exceptions from services and map them in `ApiExceptionHandler`.
- **Metrics:** name custom meters `mds.<area>.<thing>`, and keep each meter's tag keys consistent.
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
- Only one instance should run per InfluxDB bucket, or candles get overwritten.
- `MarketStreamManager` and `CandleService` are `SmartLifecycle` beans; `CandleService` starts
  first and stops last so it recovers before the first tick and flushes after the last.

## Running the app as an agent

A developer may already be running `MdsApplication` from the IDE against the same InfluxDB.
Check with `pgrep -fl com.mds.MdsApplication` first. Start your copy with a marker,
`./gradlew bootRun --args='--mds.instance=agent'`, and stop only that one:
`pkill -f 'mds.instance=agent'`. Never kill processes by class name.

## Commits and releases

Commits follow [Conventional Commits](https://www.conventionalcommits.org/), enforced by
`.commitlintrc.json` and read by semantic-release (`.releaserc.json`) to pick the next version:

```
<type>(<optional scope>): <imperative summary, lower case, no period>

<body: what and why, wrapped at 100>

BREAKING CHANGE: <what breaks and how to migrate>
```

| Type | Use for | Release |
|---|---|---|
| `feat` | new behaviour visible to API users or operators | minor |
| `fix` | bug fix | patch |
| `perf` | performance improvement | patch |
| `refactor` | restructuring without behaviour change | patch |
| `revert` | reverting a previous commit | patch |
| `docs`, `test`, `build`, `ci`, `chore`, `style` | everything else | none |

A `BREAKING CHANGE:` footer, or `!` after the type (`feat!:`), triggers a major release. Use it
for any incompatible change to the REST/SSE API, configuration keys or InfluxDB schema.

Suggested scopes: `streaming`, `prices`, `candles`, `decimal`, `metrics`, `persistence`, `api`,
`config`, `deps`.

Examples:

```
feat(candles): carry forward the last close through periods without updates
fix(streaming): resubscribe when an exchange stream completes
feat(api)!: rename POST /prices/collect to /prices/archive
```

Branch from `main`, keep commits focused, and open a pull request; don't push directly to `main`.
