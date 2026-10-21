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
| `mds.socks-proxy.host` / `.port` | `MDS_SOCKS_PROXY_HOST` / `MDS_SOCKS_PROXY_PORT` | blank (direct) / `1080` |
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

## Contributing

### Commit messages (semantic-release)

Versions and release notes are generated by [semantic-release](https://semantic-release.gitbook.io)
from commit messages on `main`, so every commit follows
[Conventional Commits](https://www.conventionalcommits.org/), as configured in `.commitlintrc.json`
and `.releaserc.json`:

```
<type>(<scope>)!: <subject>          ← title: what changes, ≤ 100 characters (aim for ≤ 72)
                                     ← blank line
<body>                               ← why, and anything a reviewer needs; wrap at 100
                                     ← blank line
BREAKING CHANGE: <what breaks and how to migrate>
Refs: #123
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
- `BREAKING CHANGE: <description>` must be spelled exactly like that (upper case, colon, space),
  at the start of a footer line, and should say how to migrate. semantic-release ignores any other
  spelling (`breaking change:`, `BREAKING CHANGE` without a colon), so the major release would
  silently not happen. The commit-msg hook rejects those spellings.
- Reference issues with footers such as `Refs: #123` or `Closes: #123`.
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
It is slightly stricter than commitlint where that protects releases (misspelt
`BREAKING CHANGE` footers, empty scopes). A rejected message is kept, so you can fix it with
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
- **The body keeps every commit message** (the squash default), so any `BREAKING CHANGE:`
  footer from the branch still triggers a major release. Edit the body into a readable summary if
  you like, but keep those footers.

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

