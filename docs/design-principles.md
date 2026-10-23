# Design principles

How we structure Java services: how many objects, how big a method, and how to write code the
JIT compiler and the garbage collector handle well. These are defaults, not laws. Departing from
one is fine when the reason is written down, in the code or in the README's "Design" section,
which records each service's own decisions.

The short version:

1. A type earns its place by holding an invariant, owning a lifecycle, hiding a boundary or naming
   a domain concept. Being a noun isn't enough.
2. A method has one purpose. A coordinating method calls its steps in order and does nothing else.
3. Hot paths are small, monomorphic and free of routine exceptions. Everything else is written
   for the reader.
4. Short-lived objects are cheap; allocation multiplied by fan-out, and memory that grows with
   traffic, are not.
5. Claim performance only with a measurement.

## 1. Objects: as few as possible, as many as needed

A type is worth its cost (a file, a name to learn, a hop when reading) when it has at least one of
these:

| Reason | Example |
|---|---|
| **An invariant** it enforces at construction | a currency pair that is always two valid codes; a fixed-point amount that is always in range |
| **A lifecycle or resource** it owns | a connection manager that connects, retries and disconnects |
| **A boundary** it hides | a repository (all database access), an external client, an adapter that stops a library's types at the edge |
| **A domain concept** people name | `Candle`, `Order`, `Invoice` |

These are not reasons on their own: "it's a noun", "we might need a second implementation", "the
method got long" (split the method instead), "every layer has one".

**Defaults**

- **Records for data**, immutable. Behaviour that derives a new value lives on the record
  (`quote.mergedWith(update)`, `candle.with(price)`). Validation goes in the compact constructor.
- **Compound map keys are private nested records** (`record Key(String source, Pair pair)`), not
  concatenated strings or maps of maps.
- **Interfaces only at real seams**: two or more implementations, or a callback or port that
  tests substitute. No `FooService` / `FooServiceImpl` pairs.
- **Enums for closed sets**, with their behaviour in a `switch` over the constant, instead of a
  class hierarchy.
- **No `Helper`, `Util` or `Manager` grab bags.** A static method lives on the type whose data it
  works on.
- **Composition over inheritance.** Extend only classes designed for it, such as framework hooks
  (`ResponseEntityExceptionHandler`).
- **One immutable model is fine at small scale.** Separate API DTOs pay off when the API must stay
  stable while the model changes. Until then, a model change is an API change, so review it as
  one.

**Signs it's too granular:**
- Types with a single method that only delegates.
- DTOs that map 1:1 to records.
- A wrapper type for every id.
- Reading one behaviour means opening five files.

**Signs it's too coarse:**
- More than about six constructor dependencies.
- Groups of methods that each use a different half of the fields.
- A class whose Javadoc needs "and" to say what it does.

Split by responsibility, not by size.

## 2. Methods: one purpose

- **Nameable without "and".** If the honest name is `validateAndSave`, it's two methods.
- **Decide or do.** A method either computes (pure: no I/O, no clock, no shared state) or acts
  (I/O, state changes). Keep decisions in pure functions; they're trivially testable and easy
  for the JIT to inline.
- **Coordinators call steps and nothing else:**

  ```java
  synchronized void flush() {
      closeEndedPeriods(clock.instant());
      write(drainCompleted());
  }
  ```

- **Guard clauses first**, then the main path, with no deep nesting.
- **Short**: one screen at most, most under 20 lines. Length is a symptom; the cure is finding
  the separate purposes, not cutting at line 20.
- **Few parameters**: four or five at most. More usually means a missing record.
- **Return values over out-parameters.** Use `Optional` for "may be absent". Use exceptions for
  failures the caller can't continue from, not for outcomes that are expected on every call.
- **Don't split below meaning.** A private method used once, whose name restates its single line,
  is noise.

## 3. Writing for the JIT

HotSpot interprets code first, then compiles the hot methods (C1, then C2) using profiles of how
they actually ran. Its most important optimisation is **inlining**: copying a callee into its
caller. Inlining unlocks the rest: escape analysis (removing allocations), constant folding,
dead-code elimination and loop optimisation. Code that inlines well gets fast.

| Do | Why |
|---|---|
| **Small methods** | Methods under 35 bytes of bytecode (`MaxInlineSize`) are always inlined; hot ones up to 325 bytes (`FreqInlineSize`). Methods over 8000 bytes aren't compiled at all (`DontCompileHugeMethods`). Single-purpose methods land naturally in the inlinable range. |
| **Monomorphic call sites** | `final` classes, records, enums, `private` and `static` methods have one possible target. A virtual or interface call that sees one or two receiver types at a site is inlined from the profile; three or more (megamorphic) becomes a table dispatch that can't be inlined. Keep hot loops over one concrete type. |
| **No routine exceptions on hot paths** | Creating an exception walks the stack to fill in its trace. If "not found" or "not ready" happens on every event, return `Optional` or a status. |
| **Loops in measured hot paths** | Streams are fine, and readable, for cold code. Each pipeline allocates several objects and lambdas, and a long one can exhaust the inlining budget. On a per-event, per-subscriber path, prefer a loop over an array or list. |
| **Constants hoisted out of hot code** | Compile `Pattern`s once (`static final`). Build fixed strings and lookup tables once, when the configuration they depend on changes, not per call. |

Don't hand-optimise code that a profile hasn't shown to be hot. The JIT already removes most of
what looks expensive in source.

## 4. Writing for the GC

Allocating a short-lived object costs a pointer bump in a thread-local buffer (TLAB), and objects
that die young cost almost nothing to collect. Escape analysis may remove them entirely. **Don't
trade clarity for avoiding a short-lived object.**

What does cost:

- **Allocation × fan-out.** A few objects per event is nothing. The same objects per event *per
  subscriber* (aggregation, serialisation, copies) scale with load. Compute once and share where
  the semantics allow.
- **Memory that grows with traffic.** Unbounded queues, a backlog per slow client, caches without
  a size limit or expiry. These become long-lived, get promoted, and make collections slow.
- **Boxing in hot collections and streams**: `Map<Long, …>`, `Stream<Integer>`, `List<Double>`.
  Use primitives, primitive streams or arrays where it's hot.
- **Strings nobody reads**: concatenating log messages at a disabled level, or building
  identical messages per call.

**Rules**

- **Bound everything that grows with traffic.** Keep the latest value, not an event log; use
  `LATEST` backpressure; cap per-client send buffers; drain queues on a schedule.
- **Primitive arithmetic for money and prices**: a fixed-point `long`, not `BigDecimal` per
  operation, and never `double`.
- **Immutable values, copied once.** `List.copyOf` at construction, then share freely; no
  defensive copies in getters.
- **Parameterised logging** (`log.debug("x {}", y)`) or a log builder; precompute fixed messages.
- **Meters registered once.** Keep the `Counter` or `Timer` reference; don't call
  `builder(...).register()` per event on a hot path.
- **Default collector and ergonomics.** Use G1 (the default) and set the heap or container memory
  limit in deployment. Tune GC flags only when a recording shows a reason, and write that reason
  down.

## 5. Measure

- Know your hot paths and their volume: per message, per message × subscriber, per scheduled
  run. Write them down in the README "Design" section.
- **JFR** is built into the JDK and cheap enough for production:

  ```bash
  java -XX:StartFlightRecording=delay=60s,duration=5m,filename=app.jfr -jar app.jar
  jfr view allocation-by-class app.jfr
  jfr view hot-methods app.jfr        # or open app.jfr in JDK Mission Control
  ```

- **GC logs**: `-Xlog:gc*:file=gc.log`. **async-profiler** for flame graphs. **JMH** for
  micro-benchmarks of value types and algorithms, never ad-hoc `System.nanoTime` loops.
- A `perf` commit carries before-and-after numbers in its body. Without them, it's a `refactor`.

## 6. Record decisions

Every service's README has a **Design** section with:

- **Decisions**: what was chosen, why, and the trade-off accepted.
- **Patterns**: the patterns used and where, plus the ones deliberately not used.
- **Hot paths and known hotspots**: what's optimised and why, what deliberately isn't, and the
  next candidates.

Update it in the same pull request as the change.
