#!/usr/bin/env bash
#
# Local development helper for mds.
#
#   scripts/dev.sh check            verify prerequisites
#   scripts/dev.sh infra            start InfluxDB (Docker) and wait until it is healthy
#   scripts/dev.sh run [args...]    check, start InfluxDB, then run the app in the foreground
#   scripts/dev.sh status           show InfluxDB and app health
#   scripts/dev.sh test             run the test suite
#   scripts/dev.sh stop             stop InfluxDB, keeping its data
#   scripts/dev.sh reset [--yes]    stop InfluxDB and delete all of its data
#   scripts/dev.sh hooks            install the git hooks in .githooks (commit message checks)
#
# Extra arguments to `run` are passed to Spring Boot, e.g.
#   scripts/dev.sh run --mds.pairs=BTC/USDT --logging.level.com.mds=DEBUG
#
# Environment variables (all optional) are read by the app as usual, see README "Configuration".
# Setting MDS_INFLUX_URL to anything other than http://localhost:8086 uses that InfluxDB instead
# of starting the local container. With MDS_SOCKS_PROXY_HOST (and optionally MDS_SOCKS_PROXY_PORT)
# set, `check` tests the proxy and reaches exchanges through it, as the app will.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_PORT="${SERVER_PORT:-8080}"
LOCAL_INFLUX_URL="http://localhost:8086"
INFLUX_URL="${MDS_INFLUX_URL:-$LOCAL_INFLUX_URL}"
MIN_JAVA=17
SOCKS_HOST="${MDS_SOCKS_PROXY_HOST:-}"
SOCKS_PORT="${MDS_SOCKS_PROXY_PORT:-1080}"

if [ -t 1 ]; then
    RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
else
    RED=""; GREEN=""; YELLOW=""; BOLD=""; RESET=""
fi

ok()   { printf '  %s✔%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '  %s!%s %s\n' "$YELLOW" "$RESET" "$*"; }
fail() { printf '  %s✘%s %s\n' "$RED" "$RESET" "$*"; }
die()  { printf '%serror:%s %s\n' "$RED" "$RESET" "$*" >&2; exit 1; }
step() { printf '%s==>%s %s\n' "$BOLD" "$RESET" "$*"; }

uses_local_influx() { [ "$INFLUX_URL" = "$LOCAL_INFLUX_URL" ]; }

# HTTP status of a GET to an exchange, through the SOCKS proxy when one is configured
exchange_status() {
    if [ -n "$SOCKS_HOST" ]; then
        curl -s -o /dev/null -w '%{http_code}' --max-time 8 --socks5-hostname "$SOCKS_HOST:$SOCKS_PORT" "$1" || true
    else
        curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1" || true
    fi
}

listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

influx_container_running() {
    [ -n "$(docker compose ps --status running --quiet influxdb 2>/dev/null)" ]
}

java_major() {
    local java_cmd="java"
    [ -n "${JAVA_HOME:-}" ] && java_cmd="$JAVA_HOME/bin/java"
    command -v "$java_cmd" >/dev/null 2>&1 || return 1
    # first line looks like: openjdk version "21.0.5" ... or java version "1.8.0_402"
    "$java_cmd" -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }'
}

cmd_check() {
    step "Checking prerequisites"
    local missing=0

    local major
    if major="$(java_major)" && [ -n "$major" ]; then
        if [ "$major" -ge "$MIN_JAVA" ]; then
            ok "Java $major (runs Gradle; the build uses a JDK 21 toolchain, downloaded automatically if absent)"
        else
            fail "Java $major found; Gradle needs Java $MIN_JAVA or newer (JDK 21 recommended)"
            missing=1
        fi
    else
        fail "Java not found; install JDK 21 (e.g. 'brew install openjdk@21' or https://adoptium.net) or set JAVA_HOME"
        missing=1
    fi

    if command -v curl >/dev/null 2>&1; then ok "curl"; else fail "curl not found"; missing=1; fi

    if uses_local_influx; then
        if ! command -v docker >/dev/null 2>&1; then
            fail "Docker not found; install Docker Desktop (or Docker Engine) to run InfluxDB locally"
            missing=1
        elif ! docker info >/dev/null 2>&1; then
            fail "Docker is installed but not running; start Docker Desktop"
            missing=1
        elif ! docker compose version >/dev/null 2>&1; then
            fail "Docker Compose v2 not found ('docker compose'); update Docker"
            missing=1
        else
            ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null), $(docker compose version --short 2>/dev/null | sed 's/^/Compose /')"
            if listening 8086 && ! influx_container_running; then
                fail "port 8086 is in use by something other than this project's InfluxDB container"
                missing=1
            else
                ok "port 8086 available for InfluxDB"
            fi
        fi
    else
        if curl -fsS --max-time 5 "$INFLUX_URL/health" >/dev/null 2>&1; then
            ok "external InfluxDB reachable at $INFLUX_URL (MDS_INFLUX_TOKEN, _ORG and _BUCKET must match it)"
        else
            fail "external InfluxDB at $INFLUX_URL is not reachable"
            missing=1
        fi
    fi

    if listening "$APP_PORT"; then
        fail "port $APP_PORT is in use; stop that process or set SERVER_PORT (is mds already running?)"
        missing=1
    else
        ok "port $APP_PORT available for the app"
    fi

    if [ -n "$SOCKS_HOST" ]; then
        if curl -s -o /dev/null --max-time 8 --socks5-hostname "$SOCKS_HOST:$SOCKS_PORT" \
                https://api.kraken.com/0/public/Time; then
            ok "SOCKS5 proxy $SOCKS_HOST:$SOCKS_PORT relays to the internet (all exchange traffic will use it)"
        else
            fail "SOCKS5 proxy $SOCKS_HOST:$SOCKS_PORT is not reachable or refuses connections"
            missing=1
        fi
    fi

    # Exchange reachability is advisory: the app skips exchanges it cannot reach.
    local name url code
    for pair in "Binance|https://api.binance.com/api/v3/ping" \
                "Kraken|https://api.kraken.com/0/public/Time" \
                "Bitstamp|https://www.bitstamp.net/api/v2/ticker/btcusd/"; do
        name="${pair%%|*}"; url="${pair#*|}"
        code="$(exchange_status "$url")"
        case "$code" in
            200) ok "$name reachable" ;;
            451|403) warn "$name refused this location (HTTP $code); in the US use MDS_EXCHANGES with binanceus instead of binance" ;;
            *) warn "$name not reachable (HTTP ${code:-none}); the app will skip it" ;;
        esac
    done

    if [ "$(git config --get core.hooksPath || true)" = ".githooks" ]; then
        ok "git commit-message hook installed"
    else
        warn "git commit-message hook not installed; run 'scripts/dev.sh hooks'"
    fi

    if [ "$missing" -ne 0 ]; then
        die "missing prerequisites, see above"
    fi
}

cmd_infra() {
    if ! uses_local_influx; then
        step "Using external InfluxDB at $INFLUX_URL"
        return
    fi
    step "Starting InfluxDB"
    docker compose up -d influxdb
    printf '    waiting for %s/health ' "$INFLUX_URL"
    local _
    for _ in $(seq 1 60); do
        if curl -fsS --max-time 2 "$INFLUX_URL/health" >/dev/null 2>&1; then
            printf '%sready%s\n' "$GREEN" "$RESET"
            ok "InfluxDB UI: $INFLUX_URL (admin / admin-password)"
            return
        fi
        printf '.'
        sleep 1
    done
    printf '\n'
    docker compose logs --tail 30 influxdb >&2 || true
    die "InfluxDB did not become healthy within 60s"
}

cmd_run() {
    cmd_check
    cmd_infra
    step "Starting mds on http://localhost:$APP_PORT (Ctrl-C to stop)"
    if [ "$#" -gt 0 ]; then
        exec ./gradlew bootRun --console=plain --args="$*"
    fi
    exec ./gradlew bootRun --console=plain
}

cmd_status() {
    step "Status"
    if curl -fsS --max-time 3 "$INFLUX_URL/health" >/dev/null 2>&1; then
        ok "InfluxDB healthy at $INFLUX_URL"
    else
        fail "InfluxDB not reachable at $INFLUX_URL"
    fi
    local health
    if health="$(curl -fsS --max-time 3 "http://localhost:$APP_PORT/actuator/health" 2>/dev/null)"; then
        # the overall status is the last top-level key: ...,"status":"UP"}
        ok "app health: $(printf '%s' "$health" | sed -E 's/.*"status":"([A-Z_]+)"\}$/\1/')"
        ok "exchanges: http://localhost:$APP_PORT/api/v1/exchanges"
    else
        fail "app not responding on http://localhost:$APP_PORT"
    fi
}

cmd_stop() {
    step "Stopping InfluxDB (data is kept)"
    docker compose stop
}

cmd_reset() {
    if [ "${1:-}" != "--yes" ]; then
        printf 'This deletes all local InfluxDB data (prices, history, candles). Continue? [y/N] '
        local answer
        read -r answer
        case "$answer" in
            y|Y|yes|YES) ;;
            *) die "aborted" ;;
        esac
    fi
    step "Removing InfluxDB container and data volumes"
    docker compose down --volumes
}

cmd_hooks() {
    step "Installing git hooks from .githooks"
    chmod +x .githooks/*
    git config core.hooksPath .githooks
    ok "commit messages are now checked against .commitlintrc.json on every commit"
}

cmd_test() {
    step "Running tests"
    exec ./gradlew test
}

usage() {
    sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
}

command="${1:-help}"
[ "$#" -gt 0 ] && shift
case "$command" in
    check)  cmd_check ;;
    infra)  cmd_infra ;;
    run)    cmd_run "$@" ;;
    status) cmd_status ;;
    stop)   cmd_stop ;;
    reset)  cmd_reset "$@" ;;
    test)   cmd_test ;;
    hooks)  cmd_hooks ;;
    help|-h|--help) usage ;;
    *) usage; die "unknown command '$command'" ;;
esac
