# wrk built for the host's CPU architecture (used by `bash bench/run.sh --native-wrk`).
# williamyeh/wrk is only published for amd64, so on Apple Silicon it runs under emulation.
FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends wrk \
    && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["wrk"]
