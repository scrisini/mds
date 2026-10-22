# mds runbook

Standard operating procedures for every event mds logs with a code. Each log line for a
catalogued event carries:

| Field | Example | Meaning |
|---|---|---|
| `event.code` | `MDS-3006` | Stable id of the event: the section of this runbook to follow |
| `event.reference` | `docs/runbook.md#mds-3006` | Link to that section |
| `http.request.id` | `3f9c…` | The request that caused it; also returned in the `X-Request-Id` header and as `requestId` in error responses. Absent for background work (exchange streams, archiving, candles) |
| `mds.*` | `mds.exchange` | Details of the event |
| `error.type`, `error.message`, `error.stack_trace` | | The exception, for errors |

Production logs are JSON in the Elastic Common Schema (`MDS_LOG_FORMAT=ecs`). Log lines without
an `event.code` come from libraries (Spring, XChange, the InfluxDB client) or are DEBUG
diagnostics; treat ERROR ones as [MDS-9001](#mds-9001) and escalate.

## Finding the log lines

Start from what you were given: a request id from a caller's error response, or a code from an
alert. Spring's ECS output may nest dotted fields (`event.code` as `"event":{"code":…}`);
Elasticsearch, Splunk and New Relic all accept the dotted name in queries.

| Tool | All lines for one request | All lines for one code |
|---|---|---|
| Kibana (KQL) | `http.request.id : "3f9c…"` | `event.code : "MDS-3006"` |
| Splunk (SPL) | `index=<index> "http.request.id"="3f9c…"` | `index=<index> "event.code"="MDS-3006"` |
| New Relic (NRQL) | ``SELECT * FROM Log WHERE `http.request.id` = '3f9c…'`` | ``SELECT * FROM Log WHERE `event.code` = 'MDS-3006'`` |
| Plain text logs | `grep 3f9c…` | `grep MDS-3006` |

Suggested alerts:

- any **ERROR**;
- `MDS-3006` or `MDS-3008` for the same `mds.exchange` repeating for more than 10 minutes (an
  exchange is unreachable or rejecting the connection);
- `MDS-3103` repeating for more than 10 minutes (candles aren't being saved);
- a deployment without one `MDS-1001` per instance.

Useful health checks: `GET /actuator/health` (InfluxDB), `GET /api/v1/exchanges` (each exchange's
connection state and last update).

## When to escalate to developers

Send the developers, in one message: the `event.code`, the `http.request.id` (if any), the time
range, and the full log lines for that request id or code (including `error.stack_trace`).

---

## Lifecycle (1xxx)

<a id="mds-1001"></a>
### MDS-1001: Service started

- **Level:** INFO. Normal at startup.
- **Means:** the instance is ready and accepting requests on `mds.port`.
- **Check:** after a deployment, one line per instance. A missing line means that instance didn't
  start: look for its last log lines.

<a id="mds-1002"></a>
### MDS-1002: Decimal precision fixed for this run

- **Level:** INFO. Normal at startup.
- **Means:** every price and volume is held with `mds.scale` decimal places, rounded with
  `mds.rounding`; values beyond ±`mds.max` can't be represented (see [MDS-2002](#mds-2002)).
- **Check:** the scale matches the deployment's `MDS_DECIMAL_SCALE`. Changing it between runs
  changes the precision of new data only.

<a id="mds-1003"></a>
### MDS-1003: All exchange connections go through the SOCKS5 proxy

- **Level:** INFO. Normal at startup when `MDS_SOCKS_PROXY_HOST` is set.
- **Means:** WebSocket streams and REST calls to exchanges use the proxy `mds.proxy`. They never
  fall back to a direct connection.
- **Check:** where policy requires a proxy, this line must be present at every startup. If you see
  [MDS-1004](#mds-1004) instead, the proxy setting is missing: treat it as a security incident.

<a id="mds-1004"></a>
### MDS-1004: No SOCKS proxy configured

- **Level:** INFO.
- **Means:** exchanges are connected directly, without a proxy.
- **Check:** expected only where no outbound proxy is required (for example locally). In an
  environment that requires one, stop the instance, set `MDS_SOCKS_PROXY_HOST` and
  `MDS_SOCKS_PROXY_PORT`, and restart; then confirm [MDS-1003](#mds-1003).

<a id="mds-1005"></a>
### MDS-1005: Configured exchange is not supported

- **Level:** WARN.
- **Means:** `MDS_EXCHANGES` lists `mds.exchange`, which mds doesn't know; it's ignored. The
  supported ids are in `mds.supported`. Other exchanges are unaffected.
- **Check:** fix the spelling in `MDS_EXCHANGES` and restart. Adding a new exchange is a code
  change: escalate as a feature request.

<a id="mds-1006"></a>
### MDS-1006: Building candles

- **Level:** INFO. Normal at startup.
- **Means:** day, week, month and year candles are being built; periods start at midnight in
  `mds.zone`.
- **Check:** the zone matches `MDS_OHLC_ZONE`. Changing the zone between runs shifts period
  boundaries for new candles only.

<a id="mds-1007"></a>
### MDS-1007: Reloaded stored candles

- **Level:** INFO. Normal at startup.
- **Means:** `mds.series` candle series were reloaded from InfluxDB and continue where they
  stopped. `mds.backfill_count` candles for periods that ended (or were missed while the service
  was down) are completed at the next flush, filled at the last close.
- **Check:** after a long outage, a large `mds.backfill_count` is expected. `mds.series` of 0 on a
  service that has run before means no candles were found: check `MDS_INFLUX_BUCKET`.

## Requests and business outcomes (2xxx)

<a id="mds-2001"></a>
### MDS-2001: Candles completed

- **Level:** INFO. Normal after each period boundary (midnight in the candle zone, plus week,
  month and year starts).
- **Means:** `mds.count` candles whose periods ended were finalised and written to InfluxDB.
- **Check:** nothing to do. If it's missing after midnight, look for [MDS-3103](#mds-3103).

<a id="mds-2002"></a>
### MDS-2002: Exchange value exceeds the decimal range

- **Level:** WARN. Logged once per exchange and field until restart.
- **Means:** `mds.exchange` sent `mds.field` = `mds.value`, larger than mds can hold at scale
  `mds.scale` (maximum `mds.max`). That field is dropped from its quotes; other fields and
  exchanges are unaffected. This usually concerns volumes of low-priced pairs.
- **Check:** if the field matters (for example `last`), lower `MDS_DECIMAL_SCALE` and restart.
  Otherwise no action.

## Dependencies (3xxx)

### Exchanges (30xx)

<a id="mds-3001"></a>
### MDS-3001: Connected to an exchange

- **Level:** INFO. Normal at startup and after each reconnect.
- **Means:** `mds.exchange` is connected and streaming `mds.pairs`.
- **Check:** one line per configured exchange after startup. Frequent repeats for one exchange
  mean its connection keeps dropping: see [MDS-3008](#mds-3008).

<a id="mds-3002"></a>
### MDS-3002: Could not initialise an exchange

- **Level:** ERROR.
- **Means:** `mds.exchange` couldn't be set up, usually because downloading its metadata at
  startup failed (exchange unreachable, proxy refusing it, or the exchange API changed). Its
  prices are missing until restart; other exchanges are unaffected, and aggregates use the rest.
- **Check:** `error.message`. A connection, timeout or proxy error: check that the exchange's host
  is reachable through the proxy (README "Outgoing proxy (SOCKS5)" lists the hosts), then restart.
- **Escalate:** if it fails again after restart while the host is reachable.

<a id="mds-3003"></a>
### MDS-3003: Could not start streaming from an exchange

- **Level:** ERROR.
- **Means:** `mds.exchange` was set up but its subscriptions couldn't be created. Its prices are
  missing until restart; other exchanges are unaffected.
- **Check:** `error.message`, then restart.
- **Escalate:** always, with the full log lines: this points at a code or library problem.

<a id="mds-3004"></a>
### MDS-3004: Exchange lists none of the configured pairs

- **Level:** WARN.
- **Means:** `mds.exchange` doesn't trade any pair in `MDS_PAIRS`, so mds doesn't connect to it.
- **Check:** expected for some combinations (for example Binance has no USD pairs). If the
  exchange should be streaming, check `MDS_PAIRS`, or remove it from `MDS_EXCHANGES`.

<a id="mds-3005"></a>
### MDS-3005: Exchange publishes no instrument list

- **Level:** INFO. Normal for Kraken and Bitstamp.
- **Means:** mds can't tell which pairs `mds.exchange` trades, so it subscribes to all configured
  pairs; pairs it doesn't trade never update.
- **Check:** nothing to do.

<a id="mds-3006"></a>
### MDS-3006: Connecting to an exchange failed

- **Level:** WARN. Retried automatically every `mds.retry_in`.
- **Means:** mds couldn't connect to `mds.exchange` (`mds.reason`). Its prices are missing until a
  retry succeeds ([MDS-3001](#mds-3001)); aggregates use the other exchanges.
- **Check:** a short burst is normal during exchange maintenance or network blips. If it repeats
  for more than 10 minutes, check that the host is reachable through the proxy, and the exchange's
  status page.
- **Escalate:** if the exchange is up and reachable but connections still fail.

<a id="mds-3007"></a>
### MDS-3007: Stopped reconnecting to an exchange

- **Level:** ERROR.
- **Means:** retrying `mds.exchange` stopped after an unexpected failure. No prices from it until
  the instance is restarted.
- **Check:** restart the instance and confirm [MDS-3001](#mds-3001) for that exchange.
- **Escalate:** always: reconnects should never stop.

<a id="mds-3008"></a>
### MDS-3008: Exchange channel stream failed

- **Level:** WARN. Resubscribed automatically every `mds.retry_in`.
- **Means:** the `mds.channel` stream (`ticker` or `trades`) for `mds.pair` on `mds.exchange`
  failed (`mds.reason`). That pair has no fresh prices from that exchange until it resubscribes.
- **Check:** occasional lines are normal (exchanges drop connections). If it repeats for more than
  10 minutes, treat it like [MDS-3006](#mds-3006).

<a id="mds-3009"></a>
### MDS-3009: Exchange channel stream ended

- **Level:** WARN. Resubscribed automatically every `mds.retry_in`.
- **Means:** the exchange closed the `mds.channel` stream for `mds.pair` on `mds.exchange`.
- **Check:** as [MDS-3008](#mds-3008).

### InfluxDB (31xx)

<a id="mds-3101"></a>
### MDS-3101: InfluxDB request for an API call failed

- **Level:** ERROR.
- **Means:** a history or candle query (`mds.method`, `mds.path`) failed in InfluxDB. The caller
  got HTTP 502 "Price archive is unavailable" with a `requestId`. Live prices and streams are
  unaffected.
- **Check:** `GET /actuator/health` (component `influx`), InfluxDB itself, and `MDS_INFLUX_URL`
  and `MDS_INFLUX_TOKEN`. Usually accompanied by [MDS-3102](#mds-3102).
- **Escalate:** if InfluxDB is healthy but the error continues: the query may be invalid.

<a id="mds-3102"></a>
### MDS-3102: Could not archive prices

- **Level:** ERROR. Repeats at every archive interval while InfluxDB is unavailable.
- **Means:** `mds.count` aggregates couldn't be written (`mds.reason`). That snapshot is lost;
  history has a gap for that interval. The next snapshot is tried at the next interval.
- **Check:** InfluxDB health, disk space, and the token's write permission on the bucket. Once it
  recovers, the lines stop by themselves.

<a id="mds-3103"></a>
### MDS-3103: Could not write candles

- **Level:** WARN. Retried at every flush (every 10 seconds by default).
- **Means:** `mds.count` candles couldn't be written (`mds.reason`). They're kept in memory and
  written once InfluxDB recovers, so nothing is lost unless the instance stops first.
- **Check:** InfluxDB health. If it lasts more than 10 minutes, fix InfluxDB before restarting
  mds: a restart now would lose the unwritten completed candles.

<a id="mds-3104"></a>
### MDS-3104: Could not reload candles at startup

- **Level:** ERROR.
- **Means:** InfluxDB was unavailable when the instance started, so candle series start from the
  next price instead of continuing. The stored candles for the current periods will be
  overwritten with ones that only cover the time since startup, and missed periods won't be
  back-filled.
- **Check:** restore InfluxDB, then restart the instance as soon as possible: it then reloads and
  continues the stored series ([MDS-1007](#mds-1007)). The longer it runs, the more of the current
  periods' data is lost.

## Unexpected failures (9xxx)

<a id="mds-9001"></a>
### MDS-9001: Unexpected failure while handling a request

- **Level:** ERROR.
- **Means:** a request (`mds.method`, `mds.path`) failed in a way the service has no rule for. The
  caller got HTTP 500 with a `requestId`. `error.*` says what failed.
- **Check:** `error.type` and `error.message`. A connection or timeout error points at a
  dependency: check `GET /actuator/health`.
- **Escalate:** always, with the full log lines for the request id.

<a id="mds-9002"></a>
### MDS-9002: Candle updates stopped

- **Level:** ERROR.
- **Means:** candle building stopped after an unexpected failure. Live prices and archiving
  continue, but candles stay frozen at their last values until restart.
- **Check:** restart the instance and confirm [MDS-1006](#mds-1006).
- **Escalate:** always, with the full log lines.

<a id="mds-9003"></a>
### MDS-9003: Background failure after its stream was closed

- **Level:** ERROR.
- **Means:** work on a stream (an exchange feed or a real-time subscription) failed after that
  stream had already been closed, so nothing could handle it. The failed piece of work
  (`mds.type`, `error.*`) was dropped; other streams are unaffected. Clients disconnecting is
  expected and isn't logged under this code.
- **Check:** whether it repeats. A single line during shutdown or a redeploy can be ignored.
- **Escalate:** if it repeats, with the full log lines: it points at a code or library problem.
