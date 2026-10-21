# mds — crypto market data aggregator

Spring Boot service that polls ticker prices from several exchanges via
[XChange](https://github.com/knowm/XChange), aggregates them per currency pair, serves them over
REST, and archives every snapshot in InfluxDB 2.x for later retrieval.

**Stack:** Java 21 · Spring Boot 4.1 · Gradle 9 (wrapper) · XChange 6.0 · InfluxDB 2.7

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
| GET | `/api/v1/exchanges` | Exchanges that connected successfully |
| GET | `/api/v1/pairs` | Pairs collected every cycle |
| GET | `/api/v1/prices` | Latest cached aggregate for each configured pair |
| GET | `/api/v1/prices/{pair}?live=false` | Aggregate for one pair (`BTC-USDT`); `live=true` bypasses the cache |
| GET | `/api/v1/prices/{pair}/{exchange}` | Live ticker from one exchange |
| POST | `/api/v1/prices/collect` | Run a collection cycle now and archive it |
| GET | `/api/v1/history/{pair}` | Archived aggregates |
| GET | `/api/v1/history/{pair}/tickers` | Archived per-exchange tickers; optional `exchange=` |
| GET | `/actuator/health` | Includes an `influx` check |

History endpoints accept `from` / `to` (ISO-8601, default: last hour), `window` (`30s`, `5m`, `1h`, `1d`…
averages rows into buckets) and `limit` (default 1000, max 10000).

```bash
curl localhost:8080/api/v1/prices/BTC-USDT
curl 'localhost:8080/api/v1/history/ETH-USD/tickers?exchange=kraken&window=5m&from=2026-10-21T00:00:00Z'
```

An aggregate contains `mean`, `median`, `vwap` (last price weighted by 24h base volume), `min`, `max`,
the best bid/ask across exchanges and which exchange quoted them, the underlying `quotes`, and
`failures` for exchanges that didn't answer. Errors are returned as RFC 9457 problem details.

## Configuration

`src/main/resources/application.yml`; the main keys can be overridden by environment variable:

| Property | Env | Default |
|---|---|---|
| `mds.exchanges` | `MDS_EXCHANGES` | `binance,kraken,bitstamp,kucoin,bitfinex` |
| `mds.pairs` | `MDS_PAIRS` | `BTC/USDT,ETH/USDT,BTC/USD,ETH/USD` |
| `mds.fetch-timeout` | | `5s` per exchange request |
| `mds.collector.enabled` / `.interval` | | `true` / `30s` |
| `mds.influx.url` / `token` / `org` / `bucket` | `MDS_INFLUX_*` | `http://localhost:8086` / `mds-dev-token` / `mds` / `prices` |

Exchanges that fail to initialise (network, geo-blocking — e.g. use `binanceus` from the US) are
logged and skipped. To add an exchange, add its `xchange-<name>` dependency in `build.gradle.kts`
and an entry in `SupportedExchange`.

## InfluxDB schema

| Measurement | Tags | Fields |
|---|---|---|
| `ticker` | `exchange`, `pair` | `last`, `bid`, `ask`, `high`, `low`, `volume`, `quote_volume` |
| `aggregate` | `pair` | `mean`, `median`, `vwap`, `min`, `max`, `best_bid`, `best_ask`, `sources` |

Points are timestamped at collection time. The compose bucket has infinite retention.

## Layout

```
com.mds
├── config/        properties, InfluxDB client, virtual-thread executor
├── exchange/      SupportedExchange enum, ExchangeRegistry (XChange instances)
├── model/         MarketPair, PriceQuote, AggregatedPrice, history rows
├── service/       PriceService (parallel fetch + cache), PriceAggregator, PriceCollector (@Scheduled)
├── persistence/   PriceRepository (Flux queries), InfluxHealthIndicator
└── web/           REST controllers, ApiExceptionHandler
```
