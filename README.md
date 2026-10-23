# mds — crypto market data aggregator

Spring Boot service that streams real-time ticker data over WebSocket from several exchanges via
[XChange](https://github.com/knowm/XChange) (`xchange-stream-*`), aggregates it per currency pair,
serves it over REST, and archives snapshots in InfluxDB 2.x for later retrieval.

```
exchange WebSockets ──► MarketStreamManager ──► QuoteBook (latest quote per exchange/pair)
                                                   │
                        REST /api/v1/prices ◄──────┤  aggregated on each request
                        SSE /api/v1/stream ◄───────┤  pushed on change, at each client's rate
                        WebSocket /api/v1/ws ◄─────┤  several pairs per socket, rate per pair
                        CandleService ◄────────────┤  OHLC per tick ──► InfluxDB (every 10s + at midnight)
                        PriceArchiver (every 5s) ◄─┘──► InfluxDB ──► REST /api/v1/history
```

**Stack:** Java 21 · Spring Boot 4.1 · Gradle 9 (wrapper) · XChange 6.0 (streaming) · InfluxDB 2.7 · Lombok

## Running locally

### Prerequisites

| Requirement | Why | Notes |
|---|---|---|
| **JDK 17+** (21 recommended) | Runs Gradle | The build compiles with a **JDK 21 toolchain**; if none is installed Gradle downloads one automatically (Foojay resolver). macOS: `brew install openjdk@21`; elsewhere [Adoptium](https://adoptium.net). Set `JAVA_HOME` if `java` isn't on your `PATH`. |
| **Docker** with Compose v2 | Runs InfluxDB 2.7 | Docker Desktop, or Docker Engine + the `docker compose` plugin. Not needed if you point the app at an existing InfluxDB. |
| **Free ports** 8080 and 8086 | App and InfluxDB | Change the app port with `SERVER_PORT`. |
| **Outbound internet** | Exchange WebSockets and metadata | Direct, or through a SOCKS5 proxy ([Outgoing proxy](#outgoing-proxy-socks5)). Unreachable exchanges are skipped. Binance blocks the US; use `MDS_EXCHANGES=binanceus,kraken,…` there. |
| `curl`, `bash` | Helper script | Preinstalled on macOS and most Linux distributions. |

Gradle itself comes from the wrapper (`./gradlew`), so it doesn't need installing, and neither do the
dependencies (downloaded on first build, which needs a few minutes and internet access).

### Quick start

```bash
scripts/dev.sh check     # verify the prerequisites above
scripts/dev.sh run       # start InfluxDB, wait for it, then run the app (Ctrl-C to stop)
```

Then, in another terminal:

```bash
scripts/dev.sh status                                   # InfluxDB and app health
curl localhost:8080/api/v1/exchanges                    # connection state per exchange
curl localhost:8080/api/v1/prices/BTC-USDT              # live aggregate
curl -N 'localhost:8080/api/v1/stream/prices/BTC-USDT?throttle=1s'
```

Exchanges connect within a few seconds of startup; until a pair's first update arrives its
endpoints return 503. The InfluxDB UI is at http://localhost:8086 (user `admin`, password
`admin-password`, org `mds`, bucket `prices`).

### `scripts/dev.sh`

| Command | What it does |
|---|---|
| `check` | Checks Java, Docker, free ports, and whether the exchanges are reachable from your network |
| `infra` | Starts InfluxDB with Docker Compose and waits until it's healthy |
| `run [args…]` | `check` + `infra`, then `./gradlew bootRun`; extra args go to Spring Boot |
| `status` | Shows InfluxDB and app health |
| `test` | Runs the test suite |
| `stop` | Stops InfluxDB; data is kept in a Docker volume |
| `reset [--yes]` | Removes the InfluxDB container **and deletes all stored data** (asks first unless `--yes`) |

Settings can be passed as Spring arguments or environment variables (see [Configuration](#configuration)):

```bash
scripts/dev.sh run --mds.pairs=BTC/USDT,ETH/USDT --logging.level.com.mds=DEBUG
MDS_EXCHANGES=kraken,bitstamp MDS_OHLC_ZONE=Australia/Sydney scripts/dev.sh run
SERVER_PORT=9090 scripts/dev.sh run
```

To use an existing InfluxDB 2.x instead of the container, set its connection details; `dev.sh`
then checks it's reachable and doesn't start Docker:

```bash
MDS_INFLUX_URL=https://influx.example.com MDS_INFLUX_TOKEN=… MDS_INFLUX_ORG=… MDS_INFLUX_BUCKET=… \
  scripts/dev.sh run
```

### Without the script

```bash
docker compose up -d            # InfluxDB on :8086 (org=mds, bucket=prices, token=mds-dev-token)
./gradlew bootRun               # API on :8080
./gradlew test                  # tests (no InfluxDB or network needed)
```

Or build a jar and run it with any Java 21+ runtime:

```bash
./gradlew bootJar
java -jar build/libs/mds-0.0.1-SNAPSHOT.jar
```

### Troubleshooting

- **`port 8080 is in use`**: another copy of mds may be running (for example from your IDE). Stop
  it or use `SERVER_PORT`. Run only one instance per InfluxDB bucket, or OHLC candles get overwritten.
- **An exchange shows `connected: false` or is missing from `/api/v1/exchanges`**: the exchange
  is unreachable or blocks your region; the log shows why. The others keep working.
- **`InfluxDB did not become healthy`**: check `docker compose logs influxdb`. To start over with an
  empty database, run `scripts/dev.sh reset`.
- **Wrong InfluxDB token**: the container's admin token is only set when its volume is first
  created. If you changed `DOCKER_INFLUXDB_INIT_ADMIN_TOKEN` in `docker-compose.yml`, run
  `scripts/dev.sh reset` so it takes effect.

## REST API

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/exchanges` | Per exchange: WebSocket connected, subscribed pairs, last update time |
| GET | `/api/v1/pairs` | Subscribed pairs |
| GET | `/api/v1/prices` | Live aggregate for each subscribed pair |
| GET | `/api/v1/prices/{pair}` | Live aggregate for one pair (`BTC-USDT`) |
| GET | `/api/v1/prices/{pair}/{exchange}` | Latest streamed quote from one exchange |
| POST | `/api/v1/prices/archive` | Archive a snapshot now |
| GET | `/api/v1/history/{pair}` | Archived aggregates |
| GET | `/api/v1/history/{pair}/tickers` | Archived per-exchange tickers; optional `exchange=` |
| GET | `/api/v1/stream/prices/{pair}?rate=2` | Server-Sent Events stream of live aggregates |
| WS | `/api/v1/ws/prices` | WebSocket: subscribe to several pairs, each at its own rate |
| GET | `/api/v1/candles/{pair}?period=day&source=index` | Stored OHLC candles (`day`, `week`, `month`, `year`), oldest first |
| GET | `/api/v1/candles/{pair}/current?source=index` | Live in-progress candles for every period |
| GET | `/actuator/health` | Includes `influx` and `streams` (up while any exchange is connected) checks |

History endpoints accept `from` / `to` (ISO-8601, default: last hour), `window` (`30s`, `5m`, `1h`, `1d`…
averages rows into buckets) and `limit` (default 1000, max 10000).

Errors are RFC 9457 problem details. Every response has an `X-Request-Id` header (the caller's, if
it sent a safe one, otherwise generated), and error bodies repeat it as `requestId`, so a caller
can quote it to support (see [Logging and support](#logging-and-support)).

### Real-time subscriptions: rates

Real-time clients can use **Server-Sent Events** (one pair per connection) or **WebSocket**
(several pairs over one connection). Both push the same aggregate JSON as `/api/v1/prices/{pair}`,
and both take a **rate**:

| Rate | Delivery |
|---|---|
| `1`, `2`, `3` … `10` | at most that many updates per second, always the latest state |
| `unlimited` | every change to the aggregate, unthrottled |
| *(none)* | `mds.stream.default-throttle`, 1 per second |

The current aggregate is sent as soon as a subscription opens, and nothing is sent while prices
don't change. Whatever the rate, a client that can't keep up gets the newest aggregate when it
catches up, never a backlog. The highest whole rate follows `mds.stream.min-throttle` (100ms, so
10/s). `rate=unlimited` can be turned off with `mds.stream.allow-unthrottled=false`. Instead of a
rate you can give `throttle`, a minimum interval (`250ms`, `1.5s`, `PT2S`), but not both.
Beyond `mds.stream.max-subscribers` subscriptions (SSE connections plus WebSocket pairs), new ones
get 503.

For scale: in testing an unthrottled BTC-USD subscription delivered about 11 updates per second,
because that's how often the cross-exchange aggregate changed.

### Server-Sent Events

```bash
curl -N 'localhost:8080/api/v1/stream/prices/BTC-USDT?rate=2'
curl -N 'localhost:8080/api/v1/stream/prices/ETH-USD?rate=unlimited'
```

Each event is named `price`, has an increasing `id`, and carries an aggregate. Connections close
after `mds.stream.timeout` (EventSource clients reconnect automatically). Invalid rates get 400.

### WebSocket

Connect to `ws://localhost:8080/api/v1/ws/prices` and send JSON commands:

```jsonc
→ {"action": "subscribe",   "pair": "BTC-USDT", "rate": 2}
→ {"action": "subscribe",   "pair": "ETH-USD",  "rate": "unlimited"}
→ {"action": "subscribe",   "pair": "BTC-USD",  "throttle": "500ms"}
→ {"action": "unsubscribe", "pair": "BTC-USDT"}

← {"type": "subscribed",   "pair": "BTC-USDT", "rate": "2/s"}
← {"type": "price",        "pair": "BTC-USDT", "seq": 1, "data": { …aggregate… }}
← {"type": "unsubscribed", "pair": "BTC-USDT"}
← {"type": "error",        "status": 400, "message": "rate must be between 1 and 10 updates per second, or 'unlimited'"}
```

- Each pair has its own rate. Subscribing to a pair again replaces its rate.
- `seq` counts price messages per pair, from 1.
- Errors (400 invalid command, 404 pair not streamed or not subscribed, 503 server full) leave the
  socket open.
- The current price is sent as soon as a subscription opens, so a `price` message can arrive just
  before its `subscribed` acknowledgement.
- A client that stops reading is disconnected with close code 1008 once 512 KB of messages are
  waiting, instead of the server buffering without limit.
- Browsers may connect only from `mds.stream.allowed-origins` (default `*`). Non-browser clients
  aren't affected.
- Idle sockets close after `mds.stream.timeout`.

From a browser or Node 22+:

```js
const ws = new WebSocket('ws://localhost:8080/api/v1/ws/prices');
ws.onopen = () => ws.send(JSON.stringify({ action: 'subscribe', pair: 'BTC-USDT', rate: 2 }));
ws.onmessage = (e) => console.log(JSON.parse(e.data));
```

```bash
curl localhost:8080/api/v1/prices/BTC-USDT
curl 'localhost:8080/api/v1/history/ETH-USD/tickers?exchange=kraken&window=5m&from=2026-10-21T00:00:00Z'
```

An aggregate contains `mean`, `median`, `vwap` (last price weighted by 24h base volume), `min`, `max`,
the best bid/ask across exchanges and which exchange quoted them, the underlying `quotes`, and
`failures` for subscribed exchanges with no update yet or none within `mds.stale-after`. Errors are
returned as RFC 9457 problem details; pairs that aren't subscribed return 404.

## Configuration

`src/main/resources/application.yml`; the main keys can be overridden by environment variable:

| Property | Env | Default |
|---|---|---|
| `mds.exchanges` | `MDS_EXCHANGES` | `binance,kraken,bitstamp,kucoin,bitfinex` |
| `mds.pairs` | `MDS_PAIRS` | `BTC/USDT,ETH/USDT,BTC/USD,ETH/USD` |
| `mds.connection-timeout` | | `10s` for metadata HTTP calls while connecting |
| `mds.reconnect-delay` | | `5s` before retrying a failed connection or stream |
| `mds.stale-after` | | `60s`; older quotes are excluded from aggregates |
| `mds.archive.enabled` / `.interval` | `MDS_ARCHIVE_INTERVAL` | `true` / `5s` |
| `mds.stream.default-throttle` / `min-throttle` / `max-throttle` | | `1s` / `100ms` (caps `rate` at 10/s) / `60s` |
| `mds.stream.timeout` / `max-subscribers` | | `30m` / `1000` |
| `mds.stream.allow-unthrottled` | `MDS_STREAM_ALLOW_UNTHROTTLED` | `true` (permits `rate=unlimited`) |
| `mds.stream.allowed-origins` | `MDS_STREAM_ALLOWED_ORIGINS` | `*` (browser origins for WebSocket) |
| `mds.decimal.scale` / `.rounding` | `MDS_DECIMAL_SCALE` / `MDS_DECIMAL_ROUNDING` | `8` / `HALF_EVEN` |
| `mds.ohlc.zone` / `.flush-interval` | `MDS_OHLC_ZONE` | `UTC` / `10s` |
| `mds.socks-proxy.host` / `.port` | `MDS_SOCKS_PROXY_HOST` / `MDS_SOCKS_PROXY_PORT` | blank (direct) / `1080` |
| `mds.influx.url` / `token` / `org` / `bucket` | `MDS_INFLUX_*` | `http://localhost:8086` / `mds-dev-token` / `mds` / `prices` |
| `logging.structured.format.console` | `MDS_LOG_FORMAT` | blank = readable text; `ecs` for ECS JSON in production |
| `logging.structured.ecs.service.environment` | `MDS_ENVIRONMENT` | `local` |

Each pair is subscribed on every exchange that lists it. Dropped sockets are reconnected by XChange;
connections that fail and streams that error or end are retried by `MarketStreamManager`. Exchanges
that fail to initialise (network, geo-blocking — e.g. use `binanceus` from the US) are logged and
skipped. To add an exchange, add its `xchange-stream-<name>` dependency in `build.gradle.kts` and an
entry in `SupportedExchange`.

**Exchange notes**

- **Bitstamp** has no ticker channel: XChange derives bid/ask from the order book, and the last
  price comes from the trade stream, so a quiet market shows bid/ask but no last price until its
  first trade. XChange 6.0.0's Bitstamp module drops every message (subscription keys don't match
  channel names); `PatchedBitstampStreamingExchange` works around it, and
  `PatchedBitstampStreamingExchangeTest` fails once upstream is fixed.
- **Kraken** and **Bitstamp** don't publish instrument metadata, so all configured pairs are
  subscribed; unlisted ones simply never update.
- `volume` is whatever each exchange's stream reports (mostly 24h base volume; KuCoin's is the last
  trade size), so VWAP is indicative.

## Outgoing proxy (SOCKS5)

Set `MDS_SOCKS_PROXY_HOST` (and `MDS_SOCKS_PROXY_PORT`, default `1080`) to send **every exchange
connection** through a SOCKS5 proxy:

- **WebSocket streams**, via XChange's streaming SOCKS settings;
- **REST calls XChange makes while connecting** (instrument metadata, KuCoin's WebSocket token),
  via rescu, whose proxy type is set to SOCKS in `src/main/resources/rescu.properties`.

Host names are resolved **by the proxy** (SOCKS5 with domain names), so the app needs neither
direct internet access nor public DNS. InfluxDB and inbound API traffic are not proxied.

```bash
MDS_SOCKS_PROXY_HOST=socks.internal MDS_SOCKS_PROXY_PORT=1080 scripts/dev.sh run
```

**Fail-closed:** with a proxy configured, startup is refused if exchange REST calls wouldn't use
SOCKS (for example if `rescu.properties` was removed), and connections that fail through the
proxy are retried through the proxy, never directly. `SocksProxyTest` checks on every build that
each supported exchange talks SOCKS5 to the configured proxy when it connects. Verified end to
end in a Docker network with no internet route or DNS except through a SOCKS5 relay: all five
exchanges connected and streamed through the proxy; with the proxy setting removed, nothing
connected; when the proxy was stopped and restarted, every exchange reconnected through it.

Limitations:

- **SOCKS5 only.** HTTP proxies (tinyproxy, Squid, …) aren't supported: XChange's WebSocket layer
  only implements SOCKS5. Supporting an HTTP `CONNECT` proxy would mean patching XChange.
- **No proxy authentication**, because XChange doesn't pass credentials to its SOCKS handler.
- An exchange whose metadata can't be fetched at startup (proxy down) is skipped until restart;
  WebSocket connections themselves retry indefinitely.

Destinations the proxy must allow (all TCP 443), as observed with the default exchanges:

| Exchange | Hosts |
|---|---|
| Binance | `api.binance.com`, `stream.binance.com` |
| Kraken | `ws.kraken.com` |
| Bitstamp | `ws.bitstamp.net` |
| KuCoin | `api.kucoin.com`, `ws-api-spot.kucoin.com` |
| Bitfinex | `api.bitfinex.com` |

`scripts/dev.sh check` tests the proxy and reaches the exchanges through it when
`MDS_SOCKS_PROXY_HOST` is set.

## OHLC candles

`CandleService` builds open/high/low/close candles for every pair, for each calendar `day`, `week`
(Monday start), `month` and `year`, from two kinds of source:

- `index` — the cross-exchange median of fresh last prices (robust to one exchange printing an outlier)
- each exchange id — that exchange's last price

Candles are updated in memory on every quote update, so highs and lows see every tick, not just
the 5-second archive snapshots. Periods start at local midnight in `mds.ohlc.zone` (default UTC).

Keeping them timely:

- every `mds.ohlc.flush-interval` (10s) all in-progress candles are upserted with `complete=false`,
  and candles whose period has ended are closed and written with `complete=true`;
- an extra flush at midnight in `mds.ohlc.zone` (the boundary every period shares) closes the
  ended candles within a second of the period end;
- a completed candle that fails to write is retried on each flush until it succeeds;
- on startup the latest candle of every series is reloaded from InfluxDB: the current period's
  continues with its open, high and low intact, and periods missed while the service was down are
  back-filled (below). A final flush runs on shutdown.

**Continuity.** Every period gets a candle, so charts have no holes. When a period ends, the next
period's candle is created immediately as a flat placeholder at the last close
(`open = high = low = close`, `updates = 0`). If a real price arrives in that period it replaces
the placeholder, so `open` is still the first traded price; if none does, the flat candle is what's
stored. Periods missed during downtime are filled the same way from the last stored close, so a
three-day outage leaves three flat daily candles rather than a gap. `updates = 0` identifies a
carried-forward candle. Only active sources are carried forward: the index and exchanges in
`mds.exchanges`, for pairs in `mds.pairs`; a removed exchange's last candle is completed and its
series stops.

Prices during downtime aren't recovered, so a candle spanning an outage reflects only what was
seen while running. Run one instance per InfluxDB bucket: two instances would overwrite each
other's candles.

```bash
curl 'localhost:8080/api/v1/candles/BTC-USDT?period=week'
curl 'localhost:8080/api/v1/candles/ETH-USD/current?source=kraken'
```

History accepts `from` / `to` (ISO-8601, candles whose period *starts* in the range) and `limit`;
`from` defaults to 30 days, 26 weeks, 24 months or 10 years back.

## Decimal precision

All prices and volumes use `com.mds.math.Decimal`, a fixed-point type backed by a `long`. Its
scale (decimal places) and rounding mode come from `mds.decimal.*` and are fixed for the life of
the process; every result is rounded to that scale. Products are computed exactly in 128 bits and
rounded once, so results match `BigDecimal` exact-then-round. `BigDecimal` is only used to convert
XChange's values on arrival (in `QuoteBook`).

The range is `±Long.MAX_VALUE / 10^scale`: about ±92 billion at scale 8, ±9.2 million at scale 12.
A field that doesn't fit (say a large 24h quote volume at a high scale) is dropped from the quote
with a one-time warning; a VWAP whose notional doesn't fit is reported as null.

Measured against `BigDecimal` with `MathContext.DECIMAL64` (the previous implementation): full
aggregation of 5 quotes 2.6x faster, mean 1.8x, add 1.2x, multiply 1.1x, sort on par.

## Logging and support

Logs are built for production support and log processors (ELK, Splunk, New Relic):

- **Every operational event has a stable code** (`MDS-nnnn`) with an SOP in
  [`docs/runbook.md`](docs/runbook.md), which also shows how to search in Kibana, Splunk and
  New Relic, and which alerts to set up.
- **JSON in production** (`MDS_LOG_FORMAT=ecs`): one Elastic Common Schema object per line, with
  `event.code`, `event.reference` (the SOP link), `http.request.id`, `mds.*` details (such as
  `mds.exchange`) and `error.*`.
- **One id per request:** `X-Request-Id` ties a caller's error response to every log line it
  caused. SSE and WebSocket streams keep the id of the request that opened them.

```bash
MDS_LOG_FORMAT=ecs scripts/dev.sh run      # JSON logs locally
```

| Code range | Area |
|---|---|
| `MDS-1xxx` | Lifecycle: startup, configuration, candle recovery |
| `MDS-2xxx` | Business outcomes: candles, dropped values |
| `MDS-30xx` | Exchanges: connections and streams |
| `MDS-31xx` | InfluxDB: queries, archiving, candle writes |
| `MDS-9xxx` | Unexpected failures |

## Metrics

Micrometer meters, visible at `/actuator/metrics/{name}` and tagged `application=mds`:

| Meter | Type | Tags |
|---|---|---|
| `http.server.requests` | timer, p50/p95/p99 | `method`, `uri`, `status`, `outcome` — every REST call |
| `mds.influx.write` | timer, p50/p95/p99 | `batch` (prices/candles), `outcome` |
| `mds.influx.points` | counter | `measurement` |
| `mds.influx.query` | timer, p50/p95/p99 | `measurement`, `outcome` |
| `mds.influx.query.rows` | distribution | `measurement` |
| `mds.stream.subscribers` | gauge | `transport` (`sse`/`websocket`) |
| `mds.stream.events` | counter | `pair`, `transport` |

```bash
curl 'localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/v1/prices/{pair}'
curl 'localhost:8080/actuator/metrics/mds.influx.write?tag=outcome:error'
```

For SSE requests `http.server.requests` measures the whole connection; WebSocket traffic isn't
in `http.server.requests`, so use `mds.stream.*` for it.

## InfluxDB schema

| Measurement | Tags | Fields |
|---|---|---|
| `ticker` | `exchange`, `pair` | `last`, `bid`, `ask`, `high`, `low`, `volume`, `quote_volume` |
| `aggregate` | `pair` | `mean`, `median`, `vwap`, `min`, `max`, `best_bid`, `best_ask`, `sources` |
| `ohlc` | `pair`, `source`, `period` | `open`, `high`, `low`, `close`, `updates` (0 = carried forward), `complete` |

Snapshots are taken every `mds.archive.interval`. Ticker points carry the time their last update
was received, so an exchange that hasn't changed between snapshots overwrites its previous point
instead of adding a duplicate. `ohlc` points are timestamped at the period start and overwritten
as the candle changes. The compose bucket has infinite retention.

## Layout

```
com.mds
├── config/        properties, InfluxDB client, clock, decimal precision, SSE executor, lifecycle logging
├── logging/       LogEvent catalogue, EventLog, LogFields, RequestIds
├── exchange/      SupportedExchange, MarketStreamManager (WebSocket lifecycle), stream health check
├── math/          Decimal (fixed-point) and its JSON serializer
├── model/         MarketPair, PriceQuote, AggregatedPrice, Candle, CandlePeriod, ExchangeStatus, history rows
├── service/       QuoteBook (live quotes), PriceService, PriceAggregator, PriceArchiver (@Scheduled),
│                  PriceStreamService (SSE/WebSocket subscriptions + rates), CandleService (OHLC)
├── persistence/   PriceRepository (Flux queries), InfluxHealthIndicator
└── web/           REST and SSE controllers, PriceWebSocketHandler, ApiExceptionHandler, RequestIdFilter
```

## Design

This section records why the code is shaped the way it is. The general rules behind it (object
granularity, single-purpose methods, code the JIT and GC handle well) are in
[`docs/design-principles.md`](docs/design-principles.md). Change a decision here in the same pull
request as the code.

### Decisions

| Decision | Why | Trade-off |
|---|---|---|
| **Latest state, not an event log.** `QuoteBook` keeps the latest quote per exchange and pair; its update stream only says *which pair changed*, and consumers read the current state. | A slow consumer skips to the latest state instead of working through a backlog. Memory is bounded by exchanges × pairs, whatever the tick rate. | Intermediate ticks aren't replayable. Candles fold every tick into high and low as it arrives, so they don't need them. |
| **Aggregate on read.** `PriceService` aggregates the fresh quotes when asked: per REST request, per stream event, per candle update. | Staleness (`mds.stale-after`) is always judged against *now*, so an exchange that went quiet drops out without a timer. | The work repeats once per real-time subscriber (see [Hotspots](#hotspots)). |
| **Fixed-point `Decimal`** (a `long` count of 10^-scale units, with one scale and rounding per JVM). | Exact arithmetic for prices, without allocating `BigDecimal`s or accumulating `double` rounding errors; comparisons are a `long` compare. | The range is ±92 billion at scale 8. Precision is JVM-global, set once at startup. |
| **Immutable records, with methods that return new values** (`PriceQuote.mergedWith`, `Candle.with`). | Quotes arrive on several exchange I/O threads at once. Immutable values can be shared without locks, and `ConcurrentHashMap.merge`/`compute` replaces one per key atomically. | One short-lived object per update, which young-generation GC handles cheaply. |
| **Latest-value throttling per subscriber** (`throttleLatest`, `BackpressureStrategy.LATEST`, `observeOn` with a buffer of 1, a virtual thread per send). | Each client gets its own rate. A slow one is sent the newest state when it catches up and never holds a queue; WebSocket sends are capped at 512 KB, after which the client is disconnected. | A fast-changing pair is sampled, not replayed, for throttled clients. |
| **One subscription path for every transport** (`PriceStreamService.open` takes a `PriceListener` callback). | Rates, the subscriber limit, metrics and slow-client handling live in one place; SSE and WebSocket only format and send. | Transports can't change throttling behaviour independently. |
| **One InfluxDB boundary** (`PriceRepository`). | Every Flux query is in one class, where interpolated values are checked and every call is timed. | Large for a single class, but it splits naturally by measurement if it grows. |
| **Ordered lifecycles** (`SmartLifecycle` phases). | `CandleService` reloads candles before the first tick and flushes after the last; `MarketStreamManager` connects in between. | Startup order lives in phase numbers, so it's documented on the beans. |
| **Fail-closed proxy.** Startup is refused if exchange REST calls wouldn't use SOCKS. | It's a security requirement: a misconfiguration must not leak traffic around the proxy. | An XChange upgrade that changes its proxy handling can't be merged until `SocksProxyTest` passes. |
| **`Optional` for an expected absence, exceptions for failures.** `PriceService.current` is empty while no exchange has a fresh price; `aggregate` throws `PriceUnavailableException` for REST, which maps it to 503. | "No price yet" is routine at startup and for quiet pairs. Streams and candles check it on every update, where throwing would capture a stack trace each time. | Two methods for one computation. |
| **Injected `Clock`.** | Tests control time (staleness, period boundaries, throttling with `TestScheduler`). | Every time-dependent bean takes a constructor argument. |

### Patterns

| Pattern | Where | Why here |
|---|---|---|
| Value object | `MarketPair`, `Decimal`, `Candle`, `StreamRate` | Validation and behaviour sit with the data; a `MarketPair` is always safe to put in a Flux query. |
| Observer (publish/subscribe) | `QuoteBook.updates()`, an RxJava serialized subject | Candles and streams react to changes without `QuoteBook` knowing about them. |
| Facade | `PriceService` over `QuoteBook`, `MarketStreamManager` and `PriceAggregator` | Controllers, streams and candles ask one question ("the aggregate for this pair") without assembling it. |
| Functional core, imperative shell | `PriceAggregator` (pure) inside `PriceService` (clock and state) | The statistics are tested with plain lists, without mocks or time. |
| Repository | `PriceRepository` | One place for storage access, query validation and storage metrics. |
| Anti-corruption layer | `QuoteBook` (XChange `BigDecimal` → `Decimal`), `PatchedBitstampStreamingExchange` | Library types and bugs stop at the boundary. The patch has a guard test that fails once upstream is fixed. |
| Enum with behaviour | `CandlePeriod` (`start`/`end` per period), `SupportedExchange`, `LogEvent` | Closed sets with a `switch` instead of a class hierarchy; call sites stay monomorphic. |
| Catalogue and fluent builder | `LogEvent.X.at(log).with(...).log()` | Every operational line has a stable code, level and SOP link, and call sites read as one sentence. |
| Decorator | `ConcurrentWebSocketSessionDecorator` | Thread-safe, bounded sends without changing the handler. |
| Template method hook | `ApiExceptionHandler.createResponseEntity` | Adds the `requestId` to Spring's own problem responses. |
| Scoped resource | `RequestIds.bind` returns a `Scope` for try-with-resources | The request id can't leak into the next task on a pooled thread. |
| Retry with fixed delay | `retryWhen`/`repeatWhen` in `MarketStreamManager` | Dropped streams and failed connections come back without manual action. |

**Deliberately not used:**
- Interface-plus-`Impl` pairs for single implementations.
- Separate DTOs mapped 1:1 from the records. The API serialises the model records, which is fine
  at this size, but a model change is an API change, so review it as one.
- A generic event bus, or abstract base controllers.
- Wrapper types for exchange ids: they're plain strings, validated at the boundaries
  (`EXCHANGE_ID`, `SupportedExchange`).

### Granularity and method size

A type exists here because it holds an invariant (`MarketPair`, `Decimal`), owns a lifecycle or
resource (`MarketStreamManager`, `CandleService`), hides a boundary (`PriceRepository`,
`QuoteBook`), or names a domain concept (`Candle`, `AggregatedPrice`). A noun alone doesn't earn
a type. Compound map keys are private nested records (`QuoteBook.Key`, `CandleService.Key`), not
concatenated strings or nested maps.

Methods have one purpose. A method that coordinates calls its steps in order and does nothing
else, for example:

```java
synchronized void flush() {            // CandleService
    closeEndedPeriods(clock.instant());
    write(drainCompleted());
}
```

These follow the same shape:
- `PriceStreamService.open`: `reserveSlot`, `eventsCounter`, then a named `Subscription`.
- `MarketStreamManager.start`: `start(exchange, pairs)`, then `create`, `attach`, `products`.
- `CandleService.recover`: `resume` per stored candle.

The split stops where a name would only restate a line of code.

### Performance: JIT and GC

The hot paths, in order of volume:
1. **Every tick:** `QuoteBook.onTicker` and `CandleService.onUpdate`. That's tens per second
   across exchanges.
2. **Every change × every subscriber:** `PriceService.current`, `PriceAggregator` and JSON
   serialisation.
3. **Every flush or snapshot:** `CandleService.flush` and `PriceRepository` writes.

What's done for the JIT and GC, and why:
- **Primitive arithmetic.** `Decimal` multiplies and divides in 128 bits on `long`s, so prices
  never allocate a `BigDecimal` after they arrive from XChange.
- **Small, single-purpose, `final`/`private`/`static` methods.** HotSpot always inlines methods
  under 35 bytes of bytecode, and hot ones up to 325 bytes. Inlining is what enables escape
  analysis, which can remove short-lived allocations altogether. Records, enums and final
  classes keep call sites monomorphic.
- **Loops in the per-subscriber path.** `PriceAggregator` sorts one array and scans it, with no
  streams, comparators or `Optional` chains per call. `MarketStreamManager.exchangesFor` reads
  an index built when connections change, instead of filtering and sorting on every
  aggregation.
- **No exceptions for routine outcomes** on per-event paths: `PriceService.current` returns
  `Optional`.
- **Constants built once.** Each `LogEvent` builds its `[CODE] summary` message and runbook link
  in its constructor; meters on per-event paths are registered once and kept.
- **Bounded memory.** Latest-value state, `LATEST` backpressure, a 512 KB WebSocket send buffer
  per client, and the completed-candle queue drained on every flush.

Not done, deliberately:
- Per-tick `QuoteBook.Key` and `MarketPair.toString()` allocations. At tens per second they cost
  nanoseconds and die young; caching them would add state for no measurable gain.
- Tuning the default G1 collector and heap ergonomics. Set the heap limit in deployment, and
  tune only with a recording that shows a reason.

#### Hotspots

The next candidates, each needing a decision before changing:
- **Aggregation and serialisation run once per subscriber per change.** With N subscribers on a
  pair, a change costs N aggregations and N JSON encodings. Sharing one aggregate per pair per
  change would make this constant. However, the aggregate's `timestamp` would then mean "time of
  the change", not "time sent", and the staleness check would run per change rather than per
  send.
- **Every subscriber's filter sees every pair's updates.** A subject per pair would route
  updates directly.

To measure, start InfluxDB (`scripts/dev.sh infra`) and record a run with JFR, skipping the
first minute so startup doesn't dominate. Put load on it while it records (real-time
subscribers, for the per-subscriber path):

```bash
./gradlew bootJar
java -XX:StartFlightRecording=delay=60s,duration=5m,filename=build/mds.jfr \
    -jar "$(ls build/libs/mds-*.jar | grep -v plain)" --mds.instance=agent
jfr view allocation-by-class build/mds.jfr   # jfr is in the JDK's bin directory
jfr view hot-methods build/mds.jfr           # or open the file in JDK Mission Control
```

Commits typed `perf` carry before-and-after numbers in the body.

## Contributing

### Commit messages (semantic-release)

Versions and release notes are generated by [semantic-release](https://semantic-release.gitbook.io)
from commit messages on `main`, so every commit follows
[Conventional Commits](https://www.conventionalcommits.org/), as configured in `.commitlintrc.json`
and `.releaserc.json`. Every push to `main` (the squash of a release PR) runs
`.github/workflows/release.yml`: it runs the tests, then semantic-release creates the `vX.Y.Z` tag
and a GitHub release with notes. Nothing is released if the tests fail, or if no commit since the
last tag calls for a release. The first release is `v1.0.0`.

```
<type>(<scope>)!: <subject>          ← title: what changes, ≤ 100 characters (aim for ≤ 72)
                                     ← blank line
<body>                               ← why, and anything a reviewer needs; wrap at 100
                                     ← blank line
Refs: #123
Co-Authored-By: Name <email>
BREAKING-CHANGE: <what breaks and how to migrate>   ← always the last line
```

**Title**

- **Type** says what kind of change it is, which decides the release:

  | Type | Use for | Release |
  |---|---|---|
  | `feat` | new behaviour for API users or operators | minor |
  | `fix` | a bug fix | patch |
  | `perf` | faster or leaner, same behaviour | patch |
  | `refactor` | restructuring with no behaviour change | patch |
  | `revert` | undoing an earlier commit | patch |
  | `docs`, `test`, `build`, `ci`, `chore`, `style` | everything else | none |
  | any type with `!` or a `BREAKING CHANGE:` footer | incompatible change | **major** |

- **Scope** (optional) names the area: `streaming`, `prices`, `candles`, `decimal`, `metrics`,
  `persistence`, `api`, `config`, `deps`. Lower-case kebab-case; other scopes only warn.
- **Subject**: imperative mood ("add", not "added"/"adds"), **starting with a lower-case letter**
  (`add OHLC candles`, not `OHLC candles…`), no full stop at the end. Describe the effect, not
  the implementation: `fix(candles): keep the open when restarting mid-day` rather than
  `fix: change rollForward loop`. It becomes a line in the release notes, so write it for readers
  of the changelog.
- **`!`** after the type/scope marks a breaking change; use it for any incompatible change to the
  REST/SSE API, configuration keys or InfluxDB schema.

**Body and footers**

- Explain *why* the change was needed and anything non-obvious about *how*; the diff shows the
  rest. Wrap lines at 100 characters.
- The breaking-change footer is spelled `BREAKING-CHANGE: ` or `BREAKING CHANGE: ` (upper case,
  singular, colon, space), at the start of a footer line, and should say how to migrate.
  semantic-release recognises only those two keywords (`.releaserc.json`), but it matches them
  loosely: in any case, with a colon or just a space, at the start of **any** body line. So the
  plural `BREAKING CHANGES:` silently loses the major release, while a prose line such as
  "Breaking change avoided by…" silently causes one. The commit-msg hook rejects both: only the
  two exact footers may start a line with "breaking change".
- **The breaking-change footer goes last.** semantic-release takes everything from it to the end
  of the message as the release note, so a `Co-Authored-By` or `Refs` line after it would be
  published in the notes. With trailers, write the hyphenated `BREAKING-CHANGE:` as the **last
  trailer**, directly after the others with no blank line (indent continuation lines): the spaced
  form isn't a valid Git trailer, and GitHub would drop the co-author credit.
- Reference issues with footers such as `Refs: #123` or `Closes: #123`. **Write `#N` only for a
  real reference:** any `#4` in a message becomes "closes #4" in the release notes, and the release
  bot comments on that issue or PR. To mention a pull request in passing, write "PR 4".
- Keep each commit to one logical change: each becomes one entry in the release notes.

| | Message | |
|---|---|---|
| ✅ | `feat(candles): carry forward the last close through quiet periods` | minor |
| ✅ | `fix(streaming): resubscribe when an exchange stream completes` | patch |
| ✅ | `feat(api)!: rename POST /prices/collect to /prices/archive` | major |
| ✅ | `docs: document local setup` | none |
| ❌ | `Add OHLC candles` | no type |
| ❌ | `feat: Add OHLC candles.` | capitalised subject, full stop |
| ❌ | `feature(Candles): add candles` | unknown type, scope not kebab-case |
| ❌ | `fix: wip` / `fix: changes` | valid, but says nothing in the release notes |

### Commit-message hook

`.githooks/commit-msg` checks every commit message before git saves the commit, and rejects any
that commitlint (and therefore CI) would reject. Install it once per clone:

```bash
scripts/dev.sh hooks        # sets git config core.hooksPath to .githooks
```

It reads the allowed types and scopes from `.commitlintrc.json`, ignores comment lines and the
`git commit -v` diff, and lets git's own merge, revert, `fixup!` and `squash!` messages through.
It is slightly stricter than commitlint where that protects releases (misspelt or plural
breaking-change footers, prose lines that start with "breaking change", empty scopes). A rejected message is kept, so you can fix it with
`git commit -e -F .git/COMMIT_EDITMSG`. It also runs for commits made from IntelliJ and other
IDEs that use git's hooks.

`git commit --no-verify` skips the hook, so the **`commitlint` GitHub Actions check**
(`.github/workflows/commitlint.yml`) checks every pull request's commits and its title on GitHub.

To test the hook after changing it, or after changing `.commitlintrc.json`:

```bash
scripts/test-commit-msg.sh                                         # hook only
COMMITLINT=/path/to/node_modules/.bin/commitlint scripts/test-commit-msg.sh   # and agreement with commitlint
```

CI runs the second form, so the hook can't drift from commitlint unnoticed.

### Branching and merging

| Branch | Purpose | Receives | Merge method |
|---|---|---|---|
| `main` | released code; every commit here is a candidate release | PRs from `develop` (and hotfixes) | **squash**: one commit per PR |
| `develop` | integration of finished work | PRs from feature branches; back-merges from `main` | **merge commit**: never squash or rebase |
| `feat/…`, `fix/…`, `docs/…` | one piece of work, branched from `develop` | your commits | — |

**Feature → `develop`: merge commit.** Open a PR from your branch into `develop` and merge it
with a merge commit. Squashing or rebasing into `develop` gives the work new commit ids, so
every branch that was cut from the old ones later conflicts with `develop`; a merge commit keeps
the history shared. Before opening the PR, bring your branch up to date with
`git merge origin/develop` (or `git rebase origin/develop` if nobody else uses the branch), and
resolve any conflicts on your branch rather than in `develop`.

**`develop` → `main`: squash, one commit per release.** Open a PR from `develop` into `main` and
squash-merge it. semantic-release then sees a single commit, so:

- **The PR title is the release commit.** It must be a valid Conventional Commit (the
  `commitlint` check enforces this) and carry the **highest** bump among the changes included:
  `feat` if any change is a feature, `!` if any is breaking, otherwise `fix`/`perf`/`refactor`.
  A `docs:` or `chore:` title means no release, whatever the branch contains.
  Example: `feat(candles): add OHLC candles and SOCKS5 proxy support`.
- **The body is the messages of the commits that are new in this release**, so any
  breaking-change footer from the branch still triggers a major release. GitHub's default body
  also lists commits already released by an earlier squash; remove those, or their footers and
  `#N` references land in the release notes a second time.

**Back-merge `main` into `develop` after every squash.** The squash commit on `main` doesn't exist
in `develop`'s history. Without a back-merge, the next `develop` → `main` PR replays work `main`
already has and conflicts on every line touched since the last release. Straight after each
merge to `main`, open a PR from `main` into `develop` and merge it with a merge commit
(`chore: back-merge main into develop`). That makes the squash commit the new common ancestor, so
the next release PR contains only new work.

**Hotfixes**: branch from `main`, PR into `main` (squash), release, then back-merge `main` into
`develop` as above.

**Never** commit directly to `main` or `develop`, force-push them, or squash into `develop`.

`scripts/github-branch-rules.sh` enforces this on GitHub with rulesets: pull requests only,
squash-only on `main` with linear history, merge-commit-only on `develop`, no force-push or
deletion, and the `commitlint` check required on both. Run it without arguments to see what it
would change, then with `--apply` (add `--create-develop` to create `develop` from `main`).

For the terms, the reasoning and the commands, see [Git guide](#git-guide).


## Git guide

How we use Git day to day, written for newcomers and as a refresher for everyone else. The rules
are in [Branching and merging](#branching-and-merging); this section explains them and lists the
commands. Commands need Git 2.23 or later (for `git switch` and `git restore`). Pull requests use
`gh`, GitHub's official CLI, because Git itself has no pull requests.

### Terms

| Term | Meaning |
|---|---|
| **Repository (repo)** | The project and its full history. Your clone is a complete copy. |
| **Remote, `origin`** | The copy on GitHub. `origin/develop` is your local record of GitHub's `develop`, as of your last `git fetch`. |
| **Commit** | A snapshot of the files plus a message, identified by a hash such as `57134e6`. Commits never change; "changing" one creates a new one with a new hash. |
| **Branch** | A movable label pointing at a commit. Committing on a branch moves its label forward. |
| **`HEAD`** | The commit you have checked out: usually the tip of the current branch. |
| **Working tree, index (staging area)** | Your files on disk, and the set of changes you've marked with `git add` for the next commit. |
| **Fetch vs pull** | `fetch` downloads new commits and updates `origin/*` without touching your files. `pull` is `fetch` followed by a merge (or rebase) into your current branch. |
| **Merge** | Joins two lines of history. If one side is just ahead of the other, Git simply moves the label (**fast-forward**). Otherwise it creates a **merge commit** with two parents. |
| **Squash merge** | Takes all of a branch's changes and makes **one new commit** from them on the target branch. The branch's own commits aren't part of the target's history. |
| **Rebase** | Replays your commits on top of another commit, creating **new** commits (new hashes) with the same changes. |
| **Conflict** | Both sides changed the same lines (or one deleted a file the other changed). Git stops and asks you to choose. |
| **Back-merge** | Merging `main` back into `develop` after a release, so `develop`'s history contains the release commit (see below). |
| **Tag** | A fixed label on one commit, such as `v1.0.0`. Unlike a branch, it never moves. |
| **Pull request (PR)** | A GitHub request to merge one branch into another, with review and checks. |
| **Force push** | Overwriting a remote branch with history that doesn't contain what's there. Never on `main` or `develop`; on your own branch, only with `--force-with-lease`. |

### Merge, rebase and squash

All three combine work; they differ in what history they leave. Start from this, where you
branched `feat/x` from `develop` at `B`, and others merged `C` and `D` since:

```
develop:  A---B---C---D
               \
feat/x:         E---F
```

**Merge** (`git merge origin/develop` on `feat/x`, or a merge-commit PR) keeps every commit and
adds `M`, which has two parents. Nothing is rewritten, so it's safe on branches others use.

```
develop:  A---B---C---D
               \       \
feat/x:         E---F---M
```

**Rebase** (`git rebase origin/develop` on `feat/x`) replays `E` and `F` on top of `D` as new
commits `E'` and `F'`. The result is a straight line, as if you'd started from `D`.

```
develop:  A---B---C---D
                       \
feat/x:                 E'--F'
```

**Squash** (a squash-merge PR) turns the branch's changes into one new commit `S` on the target.

```
main:     R---S          S = E + F as one commit; E and F stay only on feat/x
```

**When to use which:**
- **Merge to integrate.** Use it to bring `develop` into your branch, and for every PR into
  `develop`. It never rewrites shared history, so nobody else's branch breaks.
- **Rebase only your own, unshared commits:** commits you haven't pushed, or a branch nobody else
  has checked out. It gives a cleaner history and lets you tidy commits before review
  (`git rebase -i`). The golden rule is to **never rebase commits other people have**, because
  their copies still have the old hashes and their next pull duplicates or conflicts with every
  rebased commit. After rebasing a pushed branch, push with `--force-with-lease`, which refuses
  if someone else pushed in the meantime.
- **Squash for releases only** (`develop` → `main`), so semantic-release reads one commit per
  release. Never squash into `develop`. The squashed commit has a new hash, so every branch cut
  from the original commits would conflict with `develop` afterwards.

### Back-merging

A squash merge creates a commit on `main` that `develop` doesn't have:

```
main:     R-----------S            S = the release squash
           \
develop:    B---C---D---(new work)
```

Git compares branches by history, not by content. Without a back-merge, the next `develop` →
`main` PR offers `B`, `C` and `D` again and conflicts on every line they touched. The back-merge
is a merge commit `K` that makes `S` part of `develop`'s history:

```
main:     R-----------S
           \           \
develop:    B---C---D---K---(new work)
```

From then on, the next release PR contains only new work.

- **Back-merge straight after each release, before anything else merges into `develop`.** Then
  both sides have the same content and the merge is clean.
- **If work landed on `develop` first,** the back-merge conflicts wherever that work changed
  files the release also touched. If `main` holds nothing beyond the release (no hotfix), keep
  `develop`'s side for every conflict. Check this first: `git diff <release-point> origin/main`
  must print nothing, where `<release-point>` is the `develop` commit the release PR squashed
  (`gh pr view <N> --json headRefOid`). With a hotfix on `main`, resolve each conflict by hand.

### Tags vs branches

| | Branch | Tag |
|---|---|---|
| Moves? | Yes, with every commit | Never |
| Used for | Ongoing work (`develop`, `feat/x`) | Marking a release (`v1.2.0`) |
| Created by | You | The `release` workflow (semantic-release), on `main` |

- **Don't create or move release tags by hand.** semantic-release works out the next version
  from the latest `vX.Y.Z` tag, and GitHub releases are attached to tags.
- **To look at a release,** check out its tag: you get a *detached HEAD*, meaning no branch is
  checked out. Commits made there belong to no branch, so create a branch first if you want to
  change anything.
- **A hotfix for an old release** starts from its tag: `git switch -c fix/x v1.2.0`.

### Working in parallel without conflicts

- **Keep branches short-lived and small.** One logical change, merged within days. Long-lived
  branches drift and conflict.
- **Sync with `develop` often,** at least daily and always before opening a PR:
  `git fetch` then `git merge origin/develop`. Resolve conflicts on your branch, never in
  `develop`.
- **Start every branch from a fresh `develop`.** Run `git pull` on `develop` first.
- **Never commit to `main` or `develop` directly;** the rulesets enforce this.
- **Don't mix in unrelated changes:** reformatting, reordering imports, or files you happened to
  open. They conflict with everyone else's work and hide the real change.
- **Talk before touching hot files.** When two people must change the same class, agree who goes
  first, or land a small shared refactor before both branches.
- **One release at a time.** Whoever squashes `develop` into `main` back-merges immediately,
  before merging anything else into `develop`.
- **Turn on `rerere`** (below). Git then remembers how you resolved a conflict and reapplies it
  if the same conflict comes back.

### Command cheat sheet

**Once per machine and per clone**

```bash
git config --global user.name "Your Name"
git config --global user.email you@example.com
git config --global pull.ff only        # pull never creates surprise merge commits
git config --global fetch.prune true    # forget remote branches deleted on GitHub
git config --global rerere.enabled true # reuse recorded conflict resolutions
scripts/dev.sh hooks                    # in each clone: the commit-msg hook
```

**Start a change**

```bash
git switch develop
git pull                                # fast-forward to GitHub's develop
git switch -c feat/short-description    # or fix/…, docs/…, refactor/…
```

**Commit**

```bash
git status                              # what changed, what's staged
git diff                                # unstaged changes; --staged for staged ones
git add src/main/java/Foo.java          # stage explicit paths (or: git add -p to pick hunks)
git commit                              # the hook checks the message
git commit --amend                      # fix the last commit, only if not pushed yet
```

**Stay up to date** (daily, and before a PR)

```bash
git fetch
git merge origin/develop                # on your branch
# on conflict: edit the files, then
git add <resolved files>
git commit                              # completes the merge
git merge --abort                       # or give up and go back
```

**Push and open a pull request**

```bash
git push -u origin feat/short-description   # first push; later just: git push
gh pr create --base develop --fill          # title and body from your commits; edit as needed
gh pr checks --watch                        # wait for commitlint
gh pr merge --merge --delete-branch         # into develop: always a merge commit
```

**After your PR is merged**

```bash
git switch develop
git pull
git branch -d feat/short-description    # -d refuses if the branch isn't merged
```

**Release** (`develop` → `main`, then back-merge)

```bash
# the commits new since the last release; $last is the develop commit that release squashed
git fetch
last=$(gh pr list --base main --head develop --state merged --limit 1 \
    --json headRefOid -q '.[0].headRefOid')
git log --no-merges --reverse --format='* %B' "$last"..origin/develop ^origin/main \
    > release-body.txt
# edit release-body.txt: one Co-Authored-By per person at the end, breaking-change footers last
gh pr create --base main --head develop --title "feat(api): <highest bump, summary>" --body ""
gh pr merge <N> --squash --subject "<the title>" --body-file release-body.txt
# straight after: back-merge
git fetch
git switch -c chore/back-merge-main origin/develop
git merge origin/main -m "chore: back-merge main into develop"
git push -u origin chore/back-merge-main
gh pr create --base develop --title "chore: back-merge main into develop" --body ""
gh pr merge --merge --delete-branch
```

**Hotfix**

```bash
git fetch
git switch -c fix/urgent-thing origin/main
# commit, push, then
gh pr create --base main --fill
gh pr merge --squash        # release, then back-merge main into develop as above
```

**Tags**

```bash
git fetch --tags
git tag --list 'v*' --sort=-v:refname   # releases, newest first
git show v1.0.0                         # what a release contains
git describe --tags                     # the latest release before HEAD, e.g. v1.0.0-3-g43bc14f
git switch --detach v1.0.0              # look around a release (detached HEAD)
git switch -c fix/x v1.0.0              # or branch from it to change something
```

**Rebase your own, unshared branch** (optional, before review)

```bash
git fetch
git rebase origin/develop               # replay your commits on the latest develop
# on conflict: edit, git add <files>, git rebase --continue   (or git rebase --abort)
git push --force-with-lease             # only on your branch, never main or develop
```

**Look around**

```bash
git log --oneline --graph --decorate -20      # recent history as a graph
git log --oneline --no-merges "$last"..origin/develop ^origin/main  # unreleased; $last: Release
git diff origin/develop...HEAD                # what your branch changes since it forked
git show <hash>                               # one commit
git blame -L 40,60 src/main/java/Foo.java     # who last changed these lines, and when
```

**Undo**

| Situation | Command |
|---|---|
| Discard unstaged edits to a file | `git restore <file>` |
| Unstage a file, keeping the edits | `git restore --staged <file>` |
| Undo the last commit, keeping its changes staged (not pushed yet) | `git reset --soft HEAD~1` |
| Undo a commit that's already pushed | `git revert <hash>`: a new commit that reverses it |
| Put work aside to switch branches | `git stash push -m "wip"`, later `git stash pop` |
| Find a commit you "lost" (after a reset or rebase) | `git reflog`, then `git switch -c rescue <hash>` |

Prefer `revert` over `reset` for anything already pushed: `reset` rewrites history others may
have.
