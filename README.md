# 🍏 Isaac Cron ⏰

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-cron/main/isaac-cron.png" alt="isaac-cron" style="margin-right: 20px; margin-bottom: 10px;">

Scheduled prompt jobs via the shared foundation scheduler.

<br>

[![Cron](https://github.com/slagyr/isaac-cron/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-cron/actions/workflows/ci-tests.yml) 
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

Scheduled prompt jobs via the shared foundation scheduler.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Acceptance tests in
`features/` use agent + server step definitions; `spec/isaac/scheduler_steps.clj`
covers cron registration scenarios.

## What's here

- Cron job configurations (crew, cron expr, prompt, comm delivery).
- Integration with the shared scheduler for timed prompt execution.
- Support for session targeting, overrides, and cache invalidation on config changes.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-server/
  isaac-cron/   # this repo
```

```sh
bb spec
bb features
bb ci         # specs + features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-cron {:local/root "../isaac-cron"}
;; or {:git/url "https://github.com/slagyr/isaac-cron.git" :git/sha "..."}
```