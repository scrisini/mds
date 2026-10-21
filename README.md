# mds — crypto market data aggregator

Spring Boot service that streams real-time ticker data over WebSocket from several exchanges via
[XChange](https://github.com/knowm/XChange) (`xchange-stream-*`), aggregates it per currency pair,
serves it over REST, and archives snapshots in InfluxDB 2.x for later retrieval.

```
exchange WebSockets ──► MarketStreamManager ──► QuoteBook (latest quote per exchange/pair)
                                                   │
                        REST /api/v1/prices ◄──────┤  aggregated on each request
                        SSE /api/v1/stream ◄───────┤  pushed on change, throttled per client
                        CandleService ◄────────────┤  OHLC per tick ──► InfluxDB (every 10s + at midnight)
                        PriceArchiver (every 5s) ◄─┘──► InfluxDB ──► REST /api/v1/history
```

**Stack:** Java 21 · Spring Boot 4.1 · Gradle 9 (wrapper) · XChange 6.0 (streaming) · InfluxDB 2.7 · Lombok

## Run

```bash
docker compose up -d     # InfluxDB on :8086 (org=mds, bucket=prices, token=mds-dev-token)
./gradlew bootRun        # API on :8080
./gradlew test
```

InfluxDB UI: http://localhost:8086 (admin / admin-password).

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
| GET | `/api/v1/stream/prices/{pair}?throttle=1s` | Server-Sent Events stream of live aggregates |
| GET | `/api/v1/candles/{pair}?period=day&source=index` | Stored OHLC candles (`day`, `week`, `month`, `year`), oldest first |
| GET | `/api/v1/candles/{pair}/current?source=index` | Live in-progress candles for every period |
| GET | `/actuator/health` | Includes `influx` and `streams` (up while any exchange is connected) checks |

History endpoints accept `from` / `to` (ISO-8601, default: last hour), `window` (`30s`, `5m`, `1h`, `1d`…
averages rows into buckets) and `limit` (default 1000, max 10000).

Logging uses Lombok's `@Slf4j`.

### Streaming subscriptions

`GET /api/v1/stream/prices/{pair}` opens a Server-Sent Events stream. Each event is named `price`,
has an increasing `id`, and carries the same JSON as `/api/v1/prices/{pair}`. The first event is
sent immediately; after that a client receives at most one event per `throttle` interval, always
the latest state, and nothing while prices are unchanged. A slow client gets the newest aggregate
when it catches up, never a backlog.

`throttle` accepts `250ms`, `2s`, `PT1S`, … between `mds.stream.min-throttle` and
`max-throttle` (default `1s`). Connections close after `mds.stream.timeout` (EventSource clients
reconnect automatically); beyond `mds.stream.max-subscribers` new subscriptions get 503.

```bash
curl -N 'localhost:8080/api/v1/stream/prices/BTC-USDT?throttle=500ms'
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
| `mds.stream.default-throttle` / `min-throttle` / `max-throttle` | | `1s` / `100ms` / `60s` |
| `mds.stream.timeout` / `max-subscribers` | | `30m` / `1000` |
| `mds.decimal.scale` / `.rounding` | `MDS_DECIMAL_SCALE` / `MDS_DECIMAL_ROUNDING` | `8` / `HALF_EVEN` |
| `mds.ohlc.zone` / `.flush-interval` | `MDS_OHLC_ZONE` | `UTC` / `10s` |
| `mds.influx.url` / `token` / `org` / `bucket` | `MDS_INFLUX_*` | `http://localhost:8086` / `mds-dev-token` / `mds` / `prices` |

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

## Metrics

Micrometer meters, visible at `/actuator/metrics/{name}` and tagged `application=mds`:

| Meter | Type | Tags |
|---|---|---|
| `http.server.requests` | timer, p50/p95/p99 | `method`, `uri`, `status`, `outcome` — every REST call |
| `mds.influx.write` | timer, p50/p95/p99 | `batch` (prices/candles), `outcome` |
| `mds.influx.points` | counter | `measurement` |
| `mds.influx.query` | timer, p50/p95/p99 | `measurement`, `outcome` |
| `mds.influx.query.rows` | distribution | `measurement` |
| `mds.stream.subscribers` | gauge | |
| `mds.stream.events` | counter | `pair` |

```bash
curl 'localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/v1/prices/{pair}'
curl 'localhost:8080/actuator/metrics/mds.influx.write?tag=outcome:error'
```

For SSE requests `http.server.requests` measures the whole connection.

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
├── config/        properties, InfluxDB client, clock, decimal precision, SSE executor
├── exchange/      SupportedExchange, MarketStreamManager (WebSocket lifecycle), stream health check
├── math/          Decimal (fixed-point) and its JSON serializer
├── model/         MarketPair, PriceQuote, AggregatedPrice, Candle, CandlePeriod, ExchangeStatus, history rows
├── service/       QuoteBook (live quotes), PriceService, PriceAggregator, PriceArchiver (@Scheduled),
│                  PriceStreamService (SSE + throttling), CandleService (OHLC)
├── persistence/   PriceRepository (Flux queries), InfluxHealthIndicator
└── web/           REST controllers, ApiExceptionHandler
```
