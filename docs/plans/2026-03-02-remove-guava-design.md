# Design: Remove Guava from Apache Jackrabbit Oak

## Motivation

Reduce the dependency footprint. Oak currently shades Guava 33.5.0-jre into a 2.5MB+
`oak-shaded-guava` jar (relocating `com.google.*` to `org.apache.jackrabbit.guava.*`).
The actual Guava surface area used by Oak is small enough to replace with JDK standard
library equivalents and lightweight internal utilities.

## Current Guava Footprint

62 Java files import from Guava (shaded or unshaded). Usage breaks down into four categories:

| Category | Imports | Usages | Modules affected |
|----------|---------|--------|------------------|
| Preconditions (`checkArgument`, `checkNotNull`, `checkState`) | ~20 files | 503+ | Nearly all modules |
| Cache (`CacheBuilder`, `Cache`, `LoadingCache`, `Weigher`, `RemovalListener`, `CacheStats`) | ~40 files | 102 imports, 1678 usages | oak-store-document, oak-segment-tar, oak-blob-plugins, oak-core-spi, oak-blob-cloud, oak-blob-cloud-azure |
| Concurrency (`ListenableFuture`, `SettableFuture`, `Futures`) | ~5 files | 10 imports | oak-commons, oak-blob-plugins |
| Collections (`ImmutableMap`, `TreeTraverser`) | ~3 files | 6 usages | Scattered |

10 modules depend on `oak-shaded-guava`. Two Azure modules (`oak-segment-azure`,
`oak-blob-cloud-azure`) also pull unshaded Guava transitively from Azure SDK.

## Replacement Strategy: JDK-only

No new external dependencies. Each Guava usage category maps to a JDK or Oak-internal replacement:

| Guava | Replacement |
|-------|-------------|
| `Preconditions.checkNotNull(x, msg)` | `java.util.Objects.requireNonNull(x, msg)` |
| `Preconditions.checkArgument(cond, msg)` | New `oak-commons` utility: `org.apache.jackrabbit.oak.commons.Preconditions` |
| `Preconditions.checkState(cond, msg)` | Same `oak-commons` utility |
| `ImmutableMap.of(...)` | `Map.of(...)` / `Collections.unmodifiableMap()` |
| `TreeTraverser` | JDK Streams or custom iteration |
| `ListenableFuture` / `SettableFuture` | `java.util.concurrent.CompletableFuture` |
| `Futures.transform()` | `CompletableFuture.thenApply()` |
| `Cache` / `LoadingCache` / `CacheBuilder` | New Oak-internal interfaces in `oak-core-spi` backed by existing `CacheLIRS` |
| `Weigher` / `RemovalListener` / `RemovalCause` | New Oak-internal interfaces in `oak-core-spi` |
| `CacheStats` | Already exists in `oak-core-spi` — decouple from Guava |

## Execution Plan

### Parallelization

Phases 1-4 are independent (different Guava packages, no overlap) and can be executed
in parallel. Phase 5 depends on Phase 4. Phase 6 depends on all previous phases.

```
 T1 (parallel)           T2                    T3
 ┌─────────────────┐
 │ Phase 1:        │
 │ Preconditions   │────┐
 └─────────────────┘    │
 ┌─────────────────┐    │
 │ Phase 2:        │    │
 │ Collections +   │────┤
 │ Concurrency     │    │                 ┌─────────────────┐
 └─────────────────┘    ├────────────────►│ Phase 6:        │
 ┌─────────────────┐    │                 │ Remove          │
 │ Phase 4:        │    │  ┌───────────┐  │ oak-shaded-guava│
 │ Cache           │────┼─►│ Phase 5:  │  └─────────────────┘
 │ interfaces      │    │  │ Migrate   │──────────┘
 └─────────────────┘    │  │ consumers │
                        │  └───────────┘
                        │
```

### PR 1 — Preconditions (Phase 1)

**Branch:** `issue/OAK-XXXXX-remove-guava-preconditions`

1. Create `org.apache.jackrabbit.oak.commons.Preconditions` in `oak-commons` with:
   - `checkArgument(boolean expression)`
   - `checkArgument(boolean expression, Object errorMessage)`
   - `checkArgument(boolean expression, String template, Object... args)`
   - `checkState(boolean expression)`
   - `checkState(boolean expression, Object errorMessage)`
   - `checkState(boolean expression, String template, Object... args)`
   - `checkNotNull(T reference)` — delegates to `Objects.requireNonNull`
   - `checkNotNull(T reference, Object errorMessage)` — delegates to `Objects.requireNonNull`
2. Replace all imports of `org.apache.jackrabbit.guava.common.base.Preconditions`
   with `org.apache.jackrabbit.oak.commons.Preconditions` across all modules.
3. Where only `checkNotNull` is used with no message, consider replacing directly with
   `Objects.requireNonNull`.
4. Run `mvn test` on affected modules.

**Estimated scope:** ~503 call sites across nearly all modules. Mechanical find-and-replace
after the utility class is in place.

### PR 2 — Collections + Concurrency (Phases 2 & 3)

**Branch:** `issue/OAK-XXXXX-remove-guava-collections-concurrency`

**Collections (6 usages):**
1. Replace `ImmutableMap.of(...)` with `Map.of(...)` or `Collections.unmodifiableMap(new HashMap<>(...))`.
2. Replace `TreeTraverser` usage with JDK Stream-based tree traversal or a simple
   recursive helper.

**Concurrency (~10 imports):**
1. Replace `ListenableFuture` with `CompletableFuture` in all signatures.
2. Replace `SettableFuture.create()` with `new CompletableFuture<>()` and
   `set(value)` with `complete(value)`.
3. Replace `Futures.transform(future, fn, executor)` with
   `future.thenApplyAsync(fn, executor)`.
4. Update `oak-commons/FutureConverter.java` and all its consumers.

**Estimated scope:** Small. ~16 import sites total. May require updating method signatures
in a few places where `ListenableFuture` is part of a public/SPI API.

### PR 3 — Cache interfaces + consumer migration (Phases 4 & 5)

**Branch:** `issue/OAK-XXXXX-remove-guava-cache`

This is the largest and most critical PR.

**Phase 4 — Define Oak-internal cache API in `oak-core-spi`:**

Package: `org.apache.jackrabbit.oak.cache`

New interfaces/classes (mirroring Guava's contracts):
- `Cache<K, V>` — core cache interface: `getIfPresent(key)`, `get(key, callable)`,
  `put(key, value)`, `invalidate(key)`, `invalidateAll()`, `size()`, `stats()`,
  `asMap()`, `cleanUp()`
- `LoadingCache<K, V> extends Cache<K, V>` — adds `get(key)` with automatic loading,
  `getAll(keys)`, `refresh(key)`
- `CacheLoader<K, V>` — abstract class with `load(key)` method
- `Weigher<K, V>` — functional interface: `int weigh(K key, V value)`
- `RemovalListener<K, V>` — functional interface: `void onRemoval(RemovalNotification<K, V>)`
- `RemovalNotification<K, V>` — record/class with `getKey()`, `getValue()`, `getCause()`
- `RemovalCause` — enum: `EXPLICIT`, `REPLACED`, `COLLECTED`, `EXPIRED`, `SIZE`
- `Ticker` — abstract class: `long read()` returning nanoseconds
- `CacheBuilder<K, V>` — builder with `maximumSize(long)`, `maximumWeight(long)`,
  `expireAfterWrite(duration)`, `expireAfterAccess(duration)`, `weigher(Weigher)`,
  `removalListener(RemovalListener)`, `ticker(Ticker)`, `recordStats()`,
  `build()` / `build(CacheLoader)`

Refactor existing classes:
- `CacheLIRS` — change `implements LoadingCache<K,V>` from Guava's to Oak's interface
- `CacheStats` / `AbstractCacheStats` — remove Guava imports, use Oak-internal types
- `EmpiricalWeigher` — implement Oak's `Weigher` instead of Guava's

**Phase 5 — Migrate consumers module by module:**

For each module, replace:
- `org.apache.jackrabbit.guava.common.cache.*` → `org.apache.jackrabbit.oak.cache.*`

Modules in order of complexity:
1. `oak-commons` (minor cache usage)
2. `oak-blob` / `oak-blob-cloud` / `oak-blob-cloud-azure` / `oak-blob-plugins`
3. `oak-segment-tar`
4. `oak-search`
5. `oak-store-document` (most complex — 20 files)
6. `oak-run-commons`

Since the new Oak interfaces mirror Guava's API, this should be mostly mechanical
import replacement. Watch for:
- Static imports of `CacheBuilder.newBuilder()` — update to Oak's `CacheBuilder`
- `com.google.common.cache.CacheStats` vs Oak's `CacheStats` (already exists,
  may need reconciliation)
- Test classes that directly construct Guava cache objects

### PR 4 — Remove oak-shaded-guava (Phase 6)

**Branch:** `issue/OAK-XXXXX-remove-guava-shaded-module`

Prerequisites: PRs 1, 2, and 3 are merged.

1. Remove `oak-shaded-guava` from the reactor POM (`pom.xml` module list).
2. Remove all `<dependency>` declarations for `oak-shaded-guava` from every module POM.
3. Remove Guava dependency declarations from `oak-parent/pom.xml`
   (`com.google.guava:guava`, `com.google.guava:failureaccess`).
4. Handle Azure modules:
   - `oak-segment-azure` and `oak-blob-cloud-azure` pull Guava transitively via
     Azure SDK. Add `<exclusion>` for `com.google.guava:guava` on Azure dependencies,
     or verify it is only used at runtime by Azure internals and scope it appropriately.
5. Delete the `oak-shaded-guava/` directory.
6. Full build: `mvn clean install` to verify no remaining Guava references.

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Cache interface mismatch causes subtle bugs | Mirror Guava's API closely; extensive test suite already exercises cache behavior |
| Azure SDK needs unshaded Guava at runtime | Keep Guava as `runtime` scope for Azure modules only, or verify Azure SDK works without it |
| `CacheLIRS` doesn't support all `CacheBuilder` options | Audit which builder options are actually used; `CacheLIRS` already supports weighted eviction, TTL, removal listeners |
| Breaking SPI/API changes if `ListenableFuture` is in public signatures | Check if any public API exposes `ListenableFuture`; if so, this is a breaking change requiring a major version bump or deprecation period |

## Testing Strategy

Each PR must pass:
- `mvn test` on all affected modules
- `mvn clean install -DskipTests` on the full reactor (verify compilation)
- After final PR: `mvn clean install` full build with tests
