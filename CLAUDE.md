# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About This Repository

This is a fork of [suzaku-io/diode](https://github.com/suzaku-io/diode) with updated dependencies (scalajs-react 4.0.0, Scala 3 support), published to Maven Central under the `io.github.mikla` organization.

## Build Commands

```bash
# Compile all projects
sbt compile

# Run all tests
sbt test

# Run tests for a specific project
sbt diodeCoreJVM/test
sbt diodeCoreJS/test
sbt diodeDataJVM/test
sbt diodeReact/test

# Run a single test suite (uTest selects by fully qualified object name)
sbt "diodeCoreJVM/testOnly diode.CircuitTests"

# Format code (also runs automatically on compile via scalafmtOnCompile)
sbt scalafmtAll

# Check formatting without modifying
sbt scalafmtCheckAll

# Publish locally
sbt publishLocal

# Cross-compile for all Scala versions (2.13, 3.x)
sbt +compile
sbt +test
```

Warnings are fatal on both Scala versions (`-Werror` on 2.13, `-Xfatal-warnings` on 3), so any new warning breaks the build.

## Project Structure

Diode is a Scala/Scala.js library for unidirectional data flow, similar to Redux/Flux/Elm architecture.

### Modules

- **diode-core** - Core circuit, actions, effects, and model reader/writer abstractions
- **diode-data** - `Pot[A]` (Potential value) type for async data states, `PotAction`, collections
- **diode-devtools** - Browser devtools integration
- **diode-react** - scalajs-react integration with `ReactConnector` and `ModelProxy`
- **diode** - Aggregates diode-core and diode-data (has no tests of its own; `test := {}`)

Cross-compiled for JVM and JS (except diode-react which is JS-only). The sbt root project only aggregates and must never be published (`publish / skip := true`) — its artifact would collide with the `diode` module on Maven Central.

The `examples/` directory contains standalone sbt builds (not part of the root build) that still reference old upstream `io.suzaku` artifacts.

## Architecture

### Circuit Pattern

The central abstraction is `Circuit[M]` which:
- Holds immutable application state (`model: M`)
- Processes actions through `actionHandler: HandlerFunction`
- Notifies subscribers when model changes
- Runs async effects returned by handlers

### Key Types

- **`Action`** - Base trait for actions; requires `ActionType` typeclass for dispatch
- **`ActionHandler[M, T]`** - Base class for handlers scoped to part of model via `ModelRW[M, T]`
- **`ActionResult[M]`** - Handler return type: `NoChange`, `ModelUpdate`, `EffectOnly`, `ModelUpdateEffect`, etc.
- **`Effect`** - Wraps `Future[A]` for async operations; combinable with `+` (parallel), `>>` (sequential)
- **`ModelR[M, S]` / `ModelRW[M, S]`** - Read-only / read-write lenses into model
- **`Pot[A]`** - Async value states: `Empty`, `Ready`, `Pending`, `PendingStale`, `Failed`, `FailedStale`, `Unavailable`

### Zooming

Use `zoomTo` macro for concise model access:
```scala
val handler = new ActionHandler(zoomTo(_.users.selected)) { ... }
```

Equivalent to manual `zoomRW`:
```scala
circuit.zoomRW(_.users)(_.copy(users = _)).zoomRW(_.selected)(_.copy(selected = _))
```

### React Integration

Mix `ReactConnector[M]` into your circuit, then use:
- `connect(zoomFunc)` - Creates wrapper component that subscribes to model changes
- `wrap(zoomFunc)(component)` - Provides `ModelProxy` to component without subscription

## Testing

Uses uTest framework. Test files are in `*/src/test/scala/`. Run with `sbt test`.

## Scala Version Notes

- Supports Scala 2.13 and 3.x
- Version-specific code lives in separate source directories: `scala-2/` and `scala-3/` (plus `scala-2.13/` for a few files). The `zoomTo` lens-generation macro is implemented twice — `diode-core/shared/src/main/scala-2/diode/macros/GenLens.scala` (scala-reflect) and `scala-3/diode/macros/GenLens.scala` (Scala 3 quotes) — so macro changes must be made in both

## Releasing

Publishing is fully automated in CI via sbt-ci-release; never publish from a local machine. The version comes from the git tag (sbt-dynver) — there is no version to bump in build files. To release, push a `v`-prefixed tag (e.g. `v1.3.1`); the release workflow runs `sbt +ci-release` and publishes to Maven Central. See README.md for details and required repository secrets.
