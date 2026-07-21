# Memory soak

Everything stacktale retains while an app runs is **bounded by design**:

| State | Where | Cap |
|---|---|---|
| dedup stats (per fingerprint) | `Deduper` | 1024 fingerprints (access-ordered LRU) |
| story context (per correlation id) | `StoryBuffer.perCorrelation` | 256 contexts (LRU) |
| story context (per thread name) | `StoryBuffer.perThreadName` | 256 contexts (LRU) |
| last-report time (per thread name) | `ReportPipeline` | 512 threads (LRU) |
| agent captures (per throwable) | `stacktale-agent` | `WeakHashMap`, GC-bounded |

"Bounded by design" and "flat heap after an hour of churn" are different claims. The soak
proves the second.

## The harness

[`MemorySoakTest`](../stacktale/src/test/java/io/github/gabrielbbaldez/stacktale/MemorySoakTest.java)
drives the real `Logback → pipeline → writer` path under continuous churn engineered to
fill every bounded map with *ever-distinct* keys:

- **rotating thread names** (`worker-<n>`, unique per event) — churns the per-thread-name
  story and last-report maps,
- **distinct correlation ids** (`traceId=tr-<n>`) — churns the per-correlation story map,
- **an ever-distinct fingerprint** per event (a unique synthetic frame) — churns the dedup
  map — alongside **one recurring fingerprint** exercising the dedup/repeat path,
- a **1 MB file cap**, so the report file rotates constantly the whole run.

It samples the live heap (used memory after `System.gc()`) every ~2 s and asserts the mean
of the last third stays within 1.5× of the first third — i.e. no monotonic growth. A leak
in any of the maps above would show as a rising post-GC floor.

It is **not a CI test**: it is gated behind `-Dstacktale.soak=true` and skipped by every
normal build.

## Running it

```bash
# default 60s
mvn -q -pl stacktale -am test -Dstacktale.soak=true -Dtest=MemorySoakTest \
    -Dsurefire.failIfNoSpecifiedTests=false

# a full hour
mvn -q -pl stacktale -am test -Dstacktale.soak=true -Dtest=MemorySoakTest \
    -Dstacktale.soak.seconds=3600 -Dsurefire.failIfNoSpecifiedTests=false
```

## Results

JDK 21, Windows. Live heap is flat across the whole run — the bounded maps evict at their
caps and the floor never rises.

| Duration | Events | Heap samples | First third | Last third | Ratio |
|---|---|---|---|---|---|
| 20 s | 45,150 | 8 | 7.3 MB | 7.3 MB | 1.00 |
| 540 s (9 min) | 1,208,850 | 227 | 7.4 MB | 7.4 MB | 1.00 |
| 3600 s (1 h) | 8,013,750 | 1,523 | 7.4 MB | 7.4 MB | 1.00 |

Over a full hour and **8 million events** — the dedup LRU alone cycling through ~8M distinct
fingerprints (evicting >99.99% of them), correlation and thread-name maps churning the same
way, and the file rotating hundreds of times — the live set never moved off ~7.4 MB. The
heap is flat; the bounded state is bounded in practice, not just by design.
