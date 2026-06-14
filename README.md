# Isaac Cron

Scheduled crew turns for Isaac. Builtin module `:isaac.cron` contributes
`:cron` and `:tz` config schema; `isaac.cron.service` registers jobs on
the shared nexus `:scheduler` and dispatches turns through the agent bridge.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation)
(scheduler, nexus, config loader) and
[isaac-agent](https://github.com/slagyr/isaac-agent) (bridge, charge,
sessions).

## Layout

- `src/isaac/cron/` — scheduler wiring, job state, module factory
- `resources/isaac-manifest.edn` — builtin manifest and config schema
- `spec/isaac/cron/` — unit specs

Integration features (`features/cron/`) live in **isaac-server** — they
exercise the full host stack and avoid a circular dep with this repo.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-cron/       # this repo
```

```sh
bb spec    # speclj unit specs
bb ci      # same
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-cron {:local/root "../isaac-cron"}
;; or {:git/url "https://github.com/slagyr/isaac-cron.git" :git/sha "..."}
```