#!/usr/bin/env bash
# Load-tests the server with wrk, everything in Docker.
#
#   bash bench/run.sh               full run: 2 modes x keep-alive on/off x 10/100/500 connections x 3 runs of 20 s
#   bash bench/run.sh --quick       smoke test: both modes, keep-alive, 10 connections, 1 run of 5 s
#   bash bench/run.sh --native-wrk  build wrk for the host CPU instead of pulling williamyeh/wrk
#
# williamyeh/wrk is only published for amd64. Under emulation on Apple Silicon it
# crashes (exit 139), so on non-amd64 Docker hosts the native build is used automatically.
#
# The server and wrk run in separate containers on a user-defined Docker network,
# each limited to the same number of CPUs so runs are comparable.
# Results: bench/results/results.csv and bench/results/summary.md
# (quick runs go to bench/results/quick/ instead).
#
# Environment overrides: SERVER_CPUS, CLIENT_CPUS, SERVER_MEMORY, SERVER_THREADS, SERVER_QUEUE,
#                        DURATION, RUNS, WRK_IMAGE
set -euo pipefail
cd "$(dirname "$0")/.."

QUICK=false
NATIVE_WRK=false
for arg in "$@"; do
    case "$arg" in
        --quick) QUICK=true ;;
        --native-wrk) NATIVE_WRK=true ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

SERVER_IMAGE=http11-server:bench
if [ -z "${WRK_IMAGE:-}" ] && [ "$(docker version --format '{{.Server.Arch}}')" != "amd64" ]; then
    NATIVE_WRK=true
fi
WRK_IMAGE=${WRK_IMAGE:-williamyeh/wrk}
NETWORK=http11-bench-net
SERVER=http11-bench-server
CLIENT=http11-bench-wrk
SERVER_CPUS=${SERVER_CPUS:-4}
CLIENT_CPUS=${CLIENT_CPUS:-4}
SERVER_MEMORY=${SERVER_MEMORY:-2g}
SERVER_THREADS=${SERVER_THREADS:-64}
SERVER_QUEUE=${SERVER_QUEUE:-128}
WRK_THREADS=4
TARGET_PATH=/index.html
WARMUP=5s

if $QUICK; then
    DURATION=${DURATION:-5s}
    RUNS=${RUNS:-1}
    CONNECTION_LEVELS="10"
    KEEPALIVE_MODES="on"
    OUT=bench/results/quick
else
    DURATION=${DURATION:-20s}
    RUNS=${RUNS:-3}
    CONNECTION_LEVELS="10 100 500"
    KEEPALIVE_MODES="on off"
    OUT=bench/results
fi
SERVER_MODES="platform virtual"

cleanup() {
    docker rm -f "$SERVER" "$CLIENT" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- setup

echo "Building server image..."
docker build -q -t "$SERVER_IMAGE" . >/dev/null

if $NATIVE_WRK; then
    echo "Building native wrk image..."
    WRK_IMAGE=http11-wrk:native
    docker build -q -t "$WRK_IMAGE" -f bench/wrk.Dockerfile bench >/dev/null
elif ! docker image inspect "$WRK_IMAGE" >/dev/null 2>&1; then
    docker pull "$WRK_IMAGE" >/dev/null
fi

cleanup
docker network create "$NETWORK" >/dev/null
mkdir -p "$OUT/raw"
CSV="$OUT/results.csv"
echo "mode,keepalive,connections,run,req_per_sec,p50_ms,p99_ms,p90_ms,non_2xx,socket_errors" > "$CSV"

start_server() {
    local mode=$1
    docker rm -f "$SERVER" >/dev/null 2>&1 || true
    docker run -d --name "$SERVER" --network "$NETWORK" \
        --cpus="$SERVER_CPUS" --memory="$SERVER_MEMORY" \
        "$SERVER_IMAGE" --mode="$mode" --threads="$SERVER_THREADS" --queue="$SERVER_QUEUE" >/dev/null
    for _ in $(seq 1 50); do
        if docker logs "$SERVER" 2>&1 | grep -q "listening"; then return 0; fi
        sleep 0.2
    done
    echo "Server did not start:" >&2
    docker logs "$SERVER" >&2
    exit 1
}

run_wrk() {
    local connections=$1 keepalive=$2 duration=$3
    local args=(-t"$WRK_THREADS" -c"$connections" -d"$duration" --latency)
    if [ "$keepalive" = "off" ]; then
        args+=(-H "Connection: close")
    fi
    docker run --rm --name "$CLIENT" --network "$NETWORK" --cpus="$CLIENT_CPUS" \
        "$WRK_IMAGE" "${args[@]}" "http://$SERVER:8080$TARGET_PATH" 2>&1
}

# Turns one wrk report into "req_per_sec,p50_ms,p99_ms,p90_ms,non_2xx,socket_errors"
parse_wrk() {
    awk '
        function ms(v) {
            if (v ~ /us$/) { sub(/us$/, "", v); return v / 1000 }
            if (v ~ /ms$/) { sub(/ms$/, "", v); return v + 0 }
            if (v ~ /m$/)  { sub(/m$/, "", v);  return v * 60000 }
            if (v ~ /s$/)  { sub(/s$/, "", v);  return v * 1000 }
            return v
        }
        $1 == "50%" { p50 = ms($2) }
        $1 == "90%" { p90 = ms($2) }
        $1 == "99%" { p99 = ms($2) }
        /^Requests\/sec:/ { rps = $2 }
        /Non-2xx or 3xx responses:/ { non2xx = $NF }
        /Socket errors:/ {
            gsub(/,/, "")
            errors = $4 + $6 + $8 + $10
        }
        END { printf "%s,%.3f,%.3f,%.3f,%d,%d\n", rps, p50, p99, p90, non2xx, errors }
    ' "$1"
}

# ---------------------------------------------------------------- run

for mode in $SERVER_MODES; do
    for keepalive in $KEEPALIVE_MODES; do
        for connections in $CONNECTION_LEVELS; do
            # Fresh server per configuration, warmed up so the JIT has compiled the hot paths
            start_server "$mode"
            if ! run_wrk "$connections" "$keepalive" "$WARMUP" > "$OUT/raw/warmup.txt"; then
                echo "wrk failed during warm-up:" >&2
                cat "$OUT/raw/warmup.txt" >&2
                exit 1
            fi
            for run in $(seq 1 "$RUNS"); do
                raw="$OUT/raw/${mode}_ka-${keepalive}_c${connections}_run${run}.txt"
                echo "mode=$mode keepalive=$keepalive connections=$connections run=$run ($DURATION)"
                if ! run_wrk "$connections" "$keepalive" "$DURATION" > "$raw"; then
                    echo "wrk failed:" >&2
                    cat "$raw" >&2
                    exit 1
                fi
                line="$mode,$keepalive,$connections,$run,$(parse_wrk "$raw")"
                echo "  $line"
                echo "$line" >> "$CSV"
            done
        done
    done
done

# ---------------------------------------------------------------- summary

median() {
    sort -n | awk '{ v[NR] = $1 } END { if (NR % 2) print v[(NR + 1) / 2]; else print (v[NR / 2] + v[NR / 2 + 1]) / 2 }'
}

if [ "$(uname -s)" = "Darwin" ]; then
    HOST_CPU="$(sysctl -n machdep.cpu.brand_string), $(sysctl -n hw.ncpu) cores, macOS $(sw_vers -productVersion)"
else
    HOST_CPU="$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2 | xargs || uname -m), $(nproc) cores, $(uname -sr)"
fi
DOCKER_INFO=$(docker info --format '{{.OperatingSystem}} {{.NCPU}} {{.MemTotal}}' |
    awk '{ printf "%s %s, %d CPUs and %.1f GiB RAM visible to Docker", $1, $2, $3, $4 / 1073741824 }')
JAVA_VERSION=$(docker run --rm --entrypoint java "$SERVER_IMAGE" -version 2>&1 | sed 's/^/    /')
WRK_ARCH=$(docker image inspect "$WRK_IMAGE" --format '{{.Architecture}}')
DOCKER_ARCH=$(docker version --format '{{.Server.Arch}}')

SUMMARY="$OUT/summary.md"
{
    echo "# Benchmark results"
    echo
    echo "Produced by \`bash bench/run.sh$($QUICK && echo ' --quick')\` on $(date -u '+%Y-%m-%d %H:%M UTC')."
    echo "Each row is the median of $RUNS run(s) of $DURATION; raw wrk output is in \`raw/\`."
    echo
    echo "## Environment"
    echo
    echo "- Host: $HOST_CPU"
    echo "- Docker: $DOCKER_INFO"
    echo "- Server container: \`--cpus=$SERVER_CPUS --memory=$SERVER_MEMORY\`, platform mode uses $SERVER_THREADS worker threads and a queue of $SERVER_QUEUE"
    echo "- Client container: \`$WRK_IMAGE\` ($WRK_ARCH), \`--cpus=$CLIENT_CPUS\`, \`wrk -t$WRK_THREADS -d$DURATION --latency\`"
    if [ "$WRK_ARCH" != "$DOCKER_ARCH" ]; then
        echo "- Note: the wrk image is $WRK_ARCH but Docker runs $DOCKER_ARCH, so wrk runs under emulation"
        echo "  and the client may be the bottleneck."
    fi
    echo "- Target: \`GET $TARGET_PATH\` ($(wc -c < "resources${TARGET_PATH}" | xargs) bytes), $WARMUP warm-up per configuration"
    echo "- Keep-alive \"off\" means wrk sends \`Connection: close\`, so every request opens a new TCP connection"
    echo "- Java:"
    echo
    echo "$JAVA_VERSION"
    echo
    echo "## Results (medians)"
    echo
    echo "| mode | keep-alive | connections | req/s | p50 ms | p90 ms | p99 ms | non-2xx | socket errors |"
    echo "|---|---|---:|---:|---:|---:|---:|---:|---:|"
    for mode in $SERVER_MODES; do
        for keepalive in $KEEPALIVE_MODES; do
            for connections in $CONNECTION_LEVELS; do
                rows=$(awk -F, -v m="$mode" -v k="$keepalive" -v c="$connections" \
                    '$1 == m && $2 == k && $3 == c' "$CSV")
                rps=$(echo "$rows" | cut -d, -f5 | median)
                p50=$(echo "$rows" | cut -d, -f6 | median)
                p99=$(echo "$rows" | cut -d, -f7 | median)
                p90=$(echo "$rows" | cut -d, -f8 | median)
                non2xx=$(echo "$rows" | cut -d, -f9 | median)
                errors=$(echo "$rows" | cut -d, -f10 | median)
                printf "| %s | %s | %s | %.0f | %.2f | %.2f | %.2f | %.0f | %.0f |\n" \
                    "$mode" "$keepalive" "$connections" "$rps" "$p50" "$p90" "$p99" "$non2xx" "$errors"
            done
        done
    done
    echo
    echo "non-2xx responses are mostly 503s from the bounded pool when more connections arrive than"
    echo "threads + queue can hold. wrk counts them as completed requests, so read req/s together with that column."
} > "$SUMMARY"

echo
cat "$SUMMARY"
