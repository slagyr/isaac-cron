# Isaac Cron

Scheduled prompt jobs via the shared foundation scheduler.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Acceptance tests in
`features/` use agent + server step definitions; `spec/isaac/scheduler_steps.clj`
covers cron registration scenarios.

```sh
bb spec
bb features
bb ci         # specs + features
```

Sibling checkouts:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-server/
  isaac-cron/   # this repo
```