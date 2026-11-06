# Benchmark results

Produced by `bash bench/run.sh` on 2026-11-06 19:57 UTC.
Each row is the median of 3 run(s) of 20s; raw wrk output is in `raw/`.

## Environment

- Host: Apple M4 Pro, 12 cores, macOS 27.0.1
- Docker: Docker Desktop, 12 CPUs and 7.7 GiB RAM visible to Docker
- Server container: `--cpus=4 --memory=2g`, platform mode uses 64 worker threads and a queue of 128
- Client container: `http11-wrk:native` (arm64), `--cpus=4`, `wrk -t4 -d20s --latency`
- Target: `GET /index.html` (4755 bytes), 5s warm-up per configuration
- Keep-alive "off" means wrk sends `Connection: close`, so every request opens a new TCP connection
- Java:

    openjdk version "21.0.13" 2026-10-20 LTS
    OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)
    OpenJDK 64-Bit Server VM Temurin-21.0.13+9 (build 21.0.13+9-LTS, mixed mode, sharing)

## Results (medians)

| mode | keep-alive | connections | req/s | p50 ms | p90 ms | p99 ms | non-2xx | socket errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| platform | on | 10 | 103666 | 0.06 | 0.10 | 0.39 | 0 | 0 |
| platform | on | 100 | 157796 | 0.27 | 37.46 | 57.85 | 0 | 0 |
| platform | on | 500 | 154852 | 0.38 | 63.58 | 522.48 | 532781 | 25 |
| platform | off | 10 | 23924 | 0.08 | 0.54 | 1.31 | 0 | 0 |
| platform | off | 100 | 61106 | 1.38 | 2.10 | 4.49 | 0 | 0 |
| platform | off | 500 | 61799 | 1.90 | 2.85 | 536.90 | 4077 | 25 |
| virtual | on | 10 | 71351 | 0.10 | 0.14 | 0.40 | 0 | 0 |
| virtual | on | 100 | 123131 | 0.71 | 1.37 | 4.69 | 0 | 0 |
| virtual | on | 500 | 113331 | 4.48 | 6.09 | 8.87 | 0 | 0 |
| virtual | off | 10 | 20813 | 0.10 | 0.49 | 1.13 | 0 | 0 |
| virtual | off | 100 | 88233 | 0.55 | 4.54 | 18.20 | 0 | 0 |
| virtual | off | 500 | 89647 | 0.81 | 1.98 | 16.65 | 0 | 0 |

non-2xx responses are mostly 503s from the bounded pool when more connections arrive than
threads + queue can hold. wrk counts them as completed requests, so read req/s together with that column.
