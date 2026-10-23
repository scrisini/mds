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
- a deployment without one `MDS-1001` per instance;
- `MDS-3202` that isn't followed by an `MDS-3201` within 10 minutes (the cluster lost an instance
  and runs without its spare capacity);
- the `mds.cluster.members` gauge below the number of instances deployed (instances that didn't
  join split the candles);
- `MDS-1016`, or the `mds.config.restartable` gauge at 0 (the stored configuration would stop the
  next restart: fix it before deploying);
- `mds.stream.queue` p99 above 100 ms, or `mds.candles.lag` p99 above 1 s, for 5 minutes
  (subscribers or candles are falling behind: see README "Capacity" and add instances or cores).

Useful health checks: `GET /actuator/health` (InfluxDB and the cluster), `GET /api/v1/exchanges`
(each exchange's connection state and last update).

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
### MDS-1007: Reloaded a pair's stored candles

- **Level:** INFO. Normal once per pair when the cluster starts. Also when the cluster lost a pair's
  in-progress candles, after a split or after two members failed together; then look for
  [MDS-3202](#mds-3202) just before.
- **Means:** the member owning `mds.pair` reloaded `mds.series` candle series from InfluxDB. They
  continue where they were last written, at most `mds.ohlc.flush-interval` ago.
  `mds.backfill_count` candles for periods that ended meanwhile are completed at the next flush,
  filled at the last close.
- **Check:** after a long outage, a large `mds.backfill_count` is expected. `mds.series` of 0 on a
  service that has run before means no candles were found: check `MDS_INFLUX_BUCKET`.

<a id="mds-1008"></a>
### MDS-1008: Cluster member started

- **Level:** INFO. Normal at every startup.
- **Means:** this instance is a member of the Hazelcast cluster `mds.cluster`, which now has
  `mds.members` members; it listens at `mds.address`. Each pair is owned by one member, which builds
  its candles and archives its prices. Every member streams all exchanges and serves its own
  subscribers.
- **Check:** `mds.members` should equal the number of instances you run against this InfluxDB
  bucket. If it's 1 while others run, they didn't join: check `MDS_CLUSTER_MEMBERS` (or
  `MDS_CLUSTER_KUBERNETES_SERVICE_DNS`), `MDS_CLUSTER_NAME` and that the cluster port is open
  between the instances. Separate clusters on one bucket overwrite each other's candles.

<a id="mds-1009"></a>
### MDS-1009: Settings differ from the running cluster

- **Level:** ERROR. The instance stops during startup.
- **Means:** `mds.local` (this instance's decimal precision and candle zone) differs from
  `mds.cluster_settings`, recorded by the cluster's first member. Shared candles store prices at
  that precision, with periods starting in that zone, so members must agree.
- **Check:** if the change is a mistake, deploy with the cluster's values. To change them
  deliberately, stop **every** instance, then start them all with the new values.

<a id="mds-1011"></a>
### MDS-1011: Configuration loaded from AWS SSM Parameter Store

- **Level:** INFO. Normal at startup when `MDS_SSM_PATH` is set.
- **Means:** `mds.keys` parameters under `mds.path` were read and override `application.yml`.
  Environment variables and command-line arguments still override them.
- **Check:** `mds.keys` should match the parameters you expect for this environment. 0 means the
  path is empty or wrong (it is case-sensitive, e.g. `/mds/prod/`).

<a id="mds-1012"></a>
### MDS-1012: Secrets loaded from HashiCorp Vault

- **Level:** INFO. Normal at startup when `MDS_VAULT_ADDRESS` is set.
- **Means:** the secret `mds.path` was read, with `mds.keys` keys; its values override Parameter
  Store and `application.yml`. A token from a Kubernetes or AppRole login was revoked again after
  reading. Values are never logged.
- **Check:** `mds.keys` should match the keys you stored. If a key is missing, the setting falls back
  to the environment or `application.yml`.

<a id="mds-1013"></a>
### MDS-1013: Could not load configuration or secrets

- **Level:** ERROR. The instance stops during startup, before logging is set up, so this appears in
  the startup error (`Application run failed`) in plain text rather than as a JSON line.
- **Means:** `MDS_SSM_PATH` or `MDS_VAULT_ADDRESS` is set, but reading failed. The message names the
  store, the path and the reason, for example `HTTP 403: permission denied`, an unknown host or a
  timeout; never a token or secret value. The instance doesn't fall back to defaults.
- **Check:**
  - **Parameter Store:** the instance's IAM role allows `ssm:GetParametersByPath` on the path (and
    `kms:Decrypt` for SecureString), and the SSM VPC endpoint is reachable.
  - **Vault:** `MDS_VAULT_ADDRESS` is reachable and its certificate trusted (`MDS_VAULT_CA_CERT`); the
    login works (`MDS_VAULT_AUTH` and its role, token or secret id); the policy allows `read` on
    `<kv-mount>/data/<path>`.

<a id="mds-1014"></a>
### MDS-1014: Real-time subscriptions closed for shutdown

- **Level:** INFO. Expected once whenever an instance stops: a deploy, a scale-in or a restart.
- **Means:** the instance received SIGTERM and closed its `mds.closed` subscriptions before
  disconnecting from the exchanges. SSE clients got a `reconnect` event and WebSocket clients close
  code 1013; they reconnect through the load balancer, which no longer routes to this instance.
- **Check:** nothing, normally. If clients report errors during deploys, check that the load
  balancer stops routing before SIGTERM: on Kubernetes, the `preStop` sleep and the ALB
  deregistration delay; on ECS, the deregistration delay (docs/deployment.md "Stopping an
  instance").

<a id="mds-1015"></a>
### MDS-1015: Production mode refused unsafe settings

- **Level:** ERROR. The instance stops during startup.
- **Means:** `mds.mode` is `production` (`MDS_MODE`), and `mds.violations` lists settings that are
  unsafe there, each naming what to change: no `MDS_SSM_PATH` or `MDS_VAULT_ADDRESS`, the
  development InfluxDB token, text logs instead of `MDS_LOG_FORMAT=ecs`, WebSocket origins `*`, or
  `rate=unlimited` allowed.
- **Check:** fix each listed setting in Parameter Store, Vault or the deployment's environment,
  then start again. To run without these rules (a test environment), set `MDS_MODE=development`.

<a id="mds-1016"></a>
### MDS-1016: The stored configuration would stop a restart

- **Level:** ERROR. The instance keeps running on the configuration it started with; the next
  restart (a deploy, a crash, a node replacement) would fail.
- **Means:** the configuration check re-read Parameter Store and Vault and found `mds.problems`,
  for example a store that can no longer be read (`HTTP 403`, an expired Vault secret ID), a value
  that doesn't validate (`mds.decimal.scale`), a production-mode violation (see
  [MDS-1015](#mds-1015)), or a change to `mds.decimal.*` or `mds.ohlc.zone` that the running cluster
  would refuse ([MDS-1009](#mds-1009)). Repeats at every check until fixed.
- **Check:** fix what each problem names, then confirm with `curl -X POST
  <instance>:8080/actuator/configcheck` (inside the network): `"restartable": true`. Don't restart
  or deploy until then. If every instance reports it, the change was made in the store; if one
  does, look at that instance's credentials (IAM role, Vault login).

<a id="mds-1017"></a>
### MDS-1017: The stored configuration is good again

- **Level:** INFO. Follows an [MDS-1016](#mds-1016) once its problems are fixed.
- **Means:** a restart would succeed again.
- **Check:** nothing.

<a id="mds-1018"></a>
### MDS-1018: Parameters or secrets changed since startup

- **Level:** INFO. Expected after someone changes a parameter or rotates a secret.
- **Means:** `mds.keys` names the keys added, removed or changed in Parameter Store or Vault since
  this instance started (never their values). Running instances keep the values they started with;
  each picks up the change when it restarts. Logged once per set of changes.
- **Check:** if the change was meant to apply now, restart the instances one at a time (a rolling
  deploy). If nobody expected it, find out who changed the store.

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

<a id="mds-2003"></a>
### MDS-2003: Subscribers moved to less loaded instances

- **Level:** INFO. Expected after scaling out, or after a restart drew reconnecting clients to the
  same instance.
- **Means:** this instance had `mds.local` subscriptions against a fair share of `mds.fair_share`
  (`mds.total` across `mds.members` instances), so it closed `mds.closed` of its newest ones. SSE
  clients reconnect by themselves; WebSocket clients got close code 1013 and should reconnect. The
  load balancer sends them to an instance with fewer.
- **Check:** if it repeats every interval on the same instance, the load balancer isn't spreading
  connections: use least-connections balancing, or raise `mds.cluster.rebalance.tolerance`. To stop
  moving clients, set `MDS_CLUSTER_REBALANCE_ENABLED=false`.

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
- **Means:** InfluxDB was unavailable when a member had to reload candles (`mds.pair`, or every
  pair when starting the cluster), so those series start from the next price instead of
  continuing. The stored candles for the current periods will be overwritten with ones that only
  cover the time since then, and missed periods won't be back-filled.
- **Check:** restore InfluxDB, then restart the instance as soon as possible: it then reloads and
  continues the stored series ([MDS-1007](#mds-1007)). The longer it runs, the more of the current
  periods' data is lost.

### Cluster (32xx)

<a id="mds-3201"></a>
### MDS-3201: Another instance joined the cluster

- **Level:** INFO. Normal when scaling out or during a rolling deploy.
- **Means:** `mds.member` joined; the cluster has `mds.members` members. Some pairs move to it,
  with their in-progress candles, and their candles and archiving continue there.
- **Check:** no action. If instances join and leave repeatedly, look for restarts or network
  problems between them.

<a id="mds-3202"></a>
### MDS-3202: An instance left the cluster

- **Level:** WARN. Expected during a deploy or scale-in; otherwise an instance crashed or lost its
  network.
- **Means:** `mds.member` left; `mds.members` remain. Its pairs move to the members that held their
  backups, which continue the in-progress candles, so none are lost. If the backups went too (two
  members failed at once, or the cluster split), the new owner reloads the pair from InfluxDB
  ([MDS-1007](#mds-1007)), losing at most one flush interval. Its subscribers were
  disconnected and reconnect to the remaining instances. Prices that arrived during the move, a
  second or two, may be missing from the candles' high and low.
- **Check:** if it wasn't a planned stop, find out why the instance went (crash, OOM, node
  failure) and restart it. If `mds.members` is lower than the instances still running, the cluster
  has split: each part writes the same candles until Hazelcast merges them, within a minute. If it
  doesn't, check the cluster port between the instances. With one member left, another failure
  loses the in-progress candles since the last flush (`mds.ohlc.flush-interval`).

<a id="mds-3203"></a>
### MDS-3203: Could not update the shared candles

- **Level:** WARN. Logged once when updates start failing, again after they recover and fail anew.
- **Means:** applying a price for `mds.pair` to the cluster's candles failed (`mds.reason`), usually
  while a member is leaving and its pairs are moving. Prices are left out of the candles until
  updates succeed again; nothing else is affected.
- **Check:** if it doesn't clear within a minute, look for [MDS-3202](#mds-3202) and check the
  cluster port between the instances and `GET /actuator/health`.

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
