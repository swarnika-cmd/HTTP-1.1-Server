# Changelog

## v2.0.0 (Nov 2026): hardening & benchmarks

### Request parsing

- Header lookup was case-sensitive (`HashMap`), so `content-length: 5` was ignored and the body
  was read as the next request. Headers now live in a `TreeMap` with `CASE_INSENSITIVE_ORDER`.
- The body was read through a `Reader` with the platform charset, counting chars instead of
  bytes. A body with multi-byte UTF-8 characters swallowed the start of the next request.
  The request line and headers are now read from the raw `InputStream` (ISO-8859-1) and the body
  as exactly `Content-Length` bytes.
- Negative, non-numeric or conflicting duplicate `Content-Length` values crashed the handler
  (`NegativeArraySizeException`) or were ignored. They now get 400.
- `Transfer-Encoding: chunked` request bodies were not supported. They are now decoded;
  chunk extensions and trailers are ignored.
- Requests with both `Transfer-Encoding` and `Content-Length` are now rejected with 400 to
  prevent request smuggling. Other transfer codings get 501.
- `HTTP/2.0` (or any version) was echoed back with 200. Versions other than 1.0/1.1 now get 505.
- Malformed request lines, header lines without a colon, whitespace before the colon, folded
  headers, bare CR and control characters now get 400.
- Header size and count were unbounded. Request line + headers are capped at 8 KB (431, or 414
  for a long request line) and 100 fields (431). Bodies are capped at 10 MB (413).
- Absolute-form request targets (`GET http://host/path`) are accepted.

### Methods and responses

- `HEAD` returned 405. It now returns the same headers as `GET` without a body.
- 405 responses had no `Allow` header. They now list the allowed methods; unknown methods get 501.
- The `Date` header used `RFC_1123_DATE_TIME`, which prints single-digit days without a leading
  zero. It now uses the IMF-fixdate format required by RFC 7231.
- Error responses computed `Content-Length` from UTF-8 bytes but wrote the body with the
  platform charset. All responses now write the exact bytes they counted.
- Every response now has `Date`, `Server`, `Connection` and `Content-Length`; the status line is
  always `HTTP/1.1`.
- Files of an unsupported type returned 415 (which is about request bodies). They now get 403.
- Recoverable errors (404, 405, 415) no longer close a keep-alive connection.

### Connections

- HTTP/1.0 connections were kept alive by default. HTTP/1.0 now closes unless the client sends
  `Connection: keep-alive`; HTTP/1.1 stays open unless it sends `Connection: close`.
- The 30 s `SO_TIMEOUT` was reset by every byte, so a Slowloris client could hold a worker
  forever. There is now a 5 s keep-alive idle timeout, a 10 s total deadline for the request
  line and headers (408 when missed) and a limit of 100 requests per connection.
- After an error the server now shuts down its output and drains input briefly before closing,
  so clients receive the error instead of a TCP reset.

### Concurrency

- `Executors.newFixedThreadPool` has an unbounded queue, and the "queue size > 50" check before
  `submit()` was racy. The pool is now a `ThreadPoolExecutor` with an `ArrayBlockingQueue` and
  `AbortPolicy`; a rejected connection gets 503 with `Retry-After: 1`. Pool size and queue
  capacity are configurable (`--threads`, `--queue`, `THREADS`, `QUEUE_CAPACITY`).
- New `--mode=virtual` (`SERVER_MODE=virtual`) runs each connection on a virtual thread, for
  comparison in benchmarks. The bounded platform pool stays the default.
- The server is now an object with `start()`/`stop()`, so tests can run several instances on
  random ports. `stop()` closes open connections.

### Security

- The Host check was skipped for `0.0.0.0` binds, and for other binds it required the exact
  `ip:port` (so `curl localhost:8080` got 403). Now every HTTP/1.1 request must have exactly one
  `Host` header (400 otherwise), whatever the bind address; the value is not matched.
- Paths are now percent-decoded (strictly, as UTF-8) and the query string is removed before the
  traversal check. The check uses normalized and real paths, so `%2e%2e` and symlinks cannot
  escape the web root.
- Upload names used the first 4 hex digits of `Double.doubleToLongBits(Math.random())`. Those
  are mostly the exponent bits (`3fe9`, `3fee`...), so two uploads in the same second often got
  the same name and the second overwrote the first. Names are now
  `upload_<UUID>.json`, created with `CREATE_NEW`, in a separate `uploads/` directory outside the
  web root. Previously committed upload files were removed and the directory is git-ignored.

### Tooling and docs

- Replaced the 5 sequential HttpClient checks with a zero-dependency suite (`TestRunner`,
  `ParserTests`, `ServerTests`) of raw-socket tests; `run_tests.sh` / `run_tests.bat` compile and run it.
- Added `bench/run.sh` (wrk in Docker, both thread modes, keep-alive on/off, 10/100/500
  connections) writing `bench/results/results.csv` and `summary.md`.
- Added GitHub Actions CI (Temurin 21).
- Dockerfile moved to Java 21 with a multi-stage build and a JRE runtime image.
- Configuration moved to `ServerConfig` (environment variables plus `--name=value` flags).
- README rewritten. Removed unmeasured performance numbers, the "RFC 2616-compliant" claim and the
  browser demo that simulated the server with made-up data.
