# Usage

```console
mvn net.optionfactory:anarchitect-maven-plugin:check-updates   # upgradable deps/plugins report (json)
mvn net.optionfactory:anarchitect-maven-plugin:check-vulns     # OSV vulnerability report (json)
mvn net.optionfactory:anarchitect-maven-plugin:check           # architecture checks on target/classes
```

# The check-updates goal

```console
mvn -U net.optionfactory:anarchitect-maven-plugin:check-updates
```

An aggregator goal: run it from the reactor root and it analyzes every module at once
(pass `-U` so version metadata is refreshed). It considers the dependencies that are
actually under version control — those declared in each module's
`dependencyManagement`, direct dependencies that are not managed, managed plugins and
direct plugin declarations — and reports, per module, the artifacts for which a newer
release exists (`current -> latest`, release versions only: snapshots and
pre-releases are never suggested). Version lookups are cached across modules so a
reactor-wide run costs one remote query per artifact.

Two JSON reports are exported to the top-level `target/`:
`anarchitect-outdated-dependencies.json` and `anarchitect-outdated-plugins.json`,
each entry recording project, kind (dependency/plugin), coordinates, current and
latest version — suitable for consumption by CI or review tooling.

# The check-vulns goal

```console
mvn net.optionfactory:anarchitect-maven-plugin:check-vulns
```

An aggregator goal that audits every resolved artifact of every module — including
transitives — against the [OSV.dev](https://osv.dev) vulnerability database, batching
queries and deduplicating artifacts across the reactor. Vulnerable dependencies are
reported per module with the CVE identifiers, together with the dependency *trail*
(the path through which the vulnerable artifact reached the module), which is usually
the decisive information for deciding whether to exclude, bump or override it.

A consolidated `anarchitect-vulnerabilities.json` is exported to the top-level
`target/`, one entry per (module, artifact, CVE) with its trail. Note that this is a
*SAST-adjacent* report: it tells you what is on your classpath and vulnerable, not
whether your code exercises the vulnerable paths.

# The check goal

```console
mvn net.optionfactory:anarchitect-maven-plugin:check
```

ArchUnit-based architecture checks for Spring/Hibernate codebases: the goal analyzes
`target/classes` (forking compilation when invoked directly); opt into build failure
on FAILURE-severity violations with `-Danarchitect.failOnViolation=true`; rule
selection with `-Danarchitect.tags=RECOMMENDED|ALL`.

A word of warning: **these are opinionated, house rules**. Many of them encode a
specific architecture — facades as the transaction boundary, entities as a
persistence-internal detail, deterministic-by-default code — that reflects how we
build systems and the classes of bugs we have actually been bitten by. They are not
universal truths: a codebase with a different layering, a service-oriented
transaction model, or deliberate EAGER fetching will disagree with several of them,
and that is fine.

## Layering and the transaction boundary

The application layer is organized around **facades**: use-case oriented beans that own
persistence. Everything else defers to them — controllers orchestrate a single facade
call, facades do not call other facades, and repositories are only reached through the
facade layer. Facades are concrete classes, annotated with a transactional stereotype
(a `@Facade`-style meta-annotated `@Transactional` is recognized, since Spring resolves
meta-annotations the same way).

## Facades are transaction roots

Every facade method is `@Transactional`. What varies is *which kind*:

- **Persistence facades** (those from which a repository is transitively reachable, or
  that publish events consumed by transactional listeners) use plain `@Transactional`
  (REQUIRED). Because the rules also guarantee that no transactional code ever calls a
  facade, REQUIRED always *creates* the root transaction rather than joining one:
  controllers stay non-transactional, listeners that need their own transaction write
  through repositories directly, and facade-to-facade calls are forbidden.
- **Non-persistence facades** declare `propagation = NEVER`: no transaction is created
  or joined, and accidental wrapping fails fast with `IllegalTransactionStateException`
  instead of silently degrading. Reachability is computed transitively over the call
  graph, so a facade routing through services to a repository is still a persistence
  facade, and a facade publishing transactional events keeps its transaction (event
  listeners hook into the publisher's synchronization).
- Propagations that cannot work on entry points are rejected: `MANDATORY` throws when
  called from a controller, `NESTED` is unsupported by `JpaTransactionManager`.
- Transactional event listeners must declare `fallbackExecution = true` (except
  `AFTER_ROLLBACK` ones): otherwise events published from non-transactional code are
  silently dropped, not delivered immediately.

## Proxy-enforced annotations

Spring implements `@Transactional`, `@Async` and the cache annotations with proxies.
Same-class calls (including from constructors) bypass the proxy, so the annotation on
the target is silently ignored — the rules flag these self-invocations, which are a
classic source of "my transaction/async/cache never happened" bugs.

## Entities are a persistence detail

Entities are a persistence detail and stay behind the facade layer: response payloads
are DTOs, never entities, and request payloads bind to DTOs, never entities (binding
an entity hands clients mass-assignment control over persisted fields).

The remaining rules keep entities on Hibernate's good side:

- `@ManyToOne`/`@OneToOne` associations declare an explicit `fetch`: the implicit
  EAGER default causes cartesian products and N+1 selects, while entity graphs — the
  preferred per-query mechanism — can promote LAZY to eager but cannot demote static
  EAGER;
- jsonb-mapped values implement value equality throughout their graph: Hibernate
  dirty-checks jsonb by comparing it to a deep copy with `equals`, so
  identity-equality types read as always dirty, causing spurious UPDATEs and
  `@Version` bumps.

### Entities keep identity equality

Entities do not override `equals`/`hashCode`. Naive implementations break in
Hibernate's hands in two ways:

- **lazy proxies**: Hibernate hands out proxy subclasses whose class differs from
  the real entity, so `getClass()`-based equality is asymmetric
  (`a.equals(b) != b.equals(a)`), and field-based comparison triggers lazy
  initialization at unexpected times (extra queries, or a
  `LazyInitializationException` once detached);
- **mutating hash codes**: ids are typically assigned by the database at persist
  time, so an id-based `hashCode` changes mid-lifetime. An entity added to a `Set`
  or `Map` before persisting is filed under its old hash and silently disappears
  from the collection afterwards; inconsistent equality likewise confuses
  Hibernate's collection snapshot comparisons into spurious
  delete-and-reinsert transitions.

Identity equality is not a compromise here, it is sufficient by construction:
within a persistence context the session canonicalizes instances by id (one object
per row per transaction), so reference equality behaves exactly like id equality
and `Set<Entity>`/`Map<Entity, ...>` are correct for as long as the entities stay
inside the facade's session. That is the same invariant the boundary rules
enforce: since no detached entities leave the facade, the guarantee never needs to
hold outside a session.

When id semantics are needed across sessions or data sources, key on the id
(`Entity::getId` as a `Map` key, `Collectors.toMap`) or on DTOs. And if an entity
ever seems to genuinely need value equality, read it as a design signal: the logic
belongs on a business key or a DTO, not on the entity.

## Validation that validates

Request payloads are validated for real: `@Valid` is present, the validated type
actually declares constraints (an empty constraint set validates nothing), controllers
do not carry class-level `@Validated` (unified method validation makes it redundant
and changes exception mapping), endpoints declare their HTTP verbs, and handlers are
public so Spring dispatches to them.

## Determinism

Behavior must not depend on hidden environment state: the JVM default timezone
(no-arg `now()`, legacy `Date`/`Calendar`), the default charset (`new String(byte[])`,
`getBytes()`, stream readers/writers), the default locale (no-arg
`toUpperCase()`/`toLowerCase()` — the Turkish-i class of bugs), and shared mutable
date formatters in singleton beans.

## Hygiene

Weak digest and password-encoder APIs are rejected in favor of modern alternatives;
legacy synchronized collections, standard-stream logging and `printStackTrace` are
flagged; unidirectional `@OneToMany` mappings are surfaced as a possibly-valid design
decision to review; and direct use of `org.apache.commons` is discouraged in favor of
JDK or vetted equivalents (CVE history, attack surface, little value over the modern JDK).

# Organization

Rules live in topic packages under `net.optionfactory.anarchitect` (`transactions`,
`entities`, `validation`, `determinism`, `web`, `equality`, `crypto`, `logging`, `jdk`,
`dependencies`, `naming`, ...) and are registered in `Checks.makeRules`. Regression
tests with bytecode fixtures live in `ChecksTest`; the fixtures reference stub
annotations in the original package names, so no Spring jars are needed to build.
