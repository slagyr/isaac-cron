# isaac.cron — scheduled prompt jobs

You are a crew running inside Isaac. This chapter covers **isaac-cron**: it
owns the `:cron` config table and the runtime that fires each entry as a
scheduled turn. Read `isaac.foundation` first if you haven't — this chapter
assumes its vocabulary (config paths, `handbook__configure`, hot reload) and
its Scheduler section, which cron builds directly on top of. Crew, comm, and
session concepts referenced below belong to `isaac.agent`; this chapter
covers them only as far as a cron job configures them.

A cron job is a scheduled prompt: at a cron-expression time, Isaac starts a
turn — as if a user had sent that prompt — on behalf of a configured crew
member, and optionally delivers the reply out through a comm. Nothing here
runs outside the server process (`isaac server`); a plain CLI command never
fires a job.

## Cron jobs

Each job is an entry in the `:cron` table, keyed by a job name you choose
(`cron.watch-report`, `cron.hull-check`, ...). Like any entity table, a job
lives inline in `isaac.edn`, as its own `config/cron/<name>.edn`, or as
`config/cron/<name>.md` with YAML frontmatter — the same file-or-directory
rule as every other config key (`isaac.foundation` → Files).

Fields on a job:

| Field | Type | Meaning |
|---|---|---|
| `crew` | id | Crew member the turn runs as. Must name a crew that exists. |
| `expr` | string | 5-field cron expression (minute hour day month weekday). |
| `prompt` | string | The prompt text sent when the job fires — required, inline or via companion markdown (see Prompt content, below). |
| `comm` | string | Comm to deliver the reply through. Optional; must name a comm actually configured under `comms`. |
| `to` | string | Recipient on that comm. Required for delivery when `comm` is set — the format (a channel id, a handle, ...) is defined by the comm itself `[verify]`. |
| `session` | list of strings | Explicit session id(s) to target; the first is used. |
| `session-tags` | list of keywords | Tags the target session must carry, all of them (AND). |
| `prefer` | `:recent` or `:oldest` | Tiebreak when more than one session matches. |
| `create` | `:never`, `:if-missing`, or `:always` | Session-creation policy. Cron's own default is `:always` — a fresh session every fire — unlike a live chat session. |
| `with-crew` | string | Overrides `crew` for this scheduled turn only. |
| `with-model` | string | Overrides the model for this scheduled turn only. |
| `with-effort` | int | Overrides effort for this scheduled turn only. |
| `with-context-mode` | keyword | Overrides context mode for this scheduled turn only. |

**How to change it.** Set fields with `handbook__configure`, same as any
config path:

```
config set cron.watch-report.expr "0 6 * * *"
config set cron.watch-report.crew cordelia
config set cron.watch-report.comm longwave
config set cron.watch-report.to captain
```

Fields spanning several jobs, or a job's several required fields at once,
land in one call the way `isaac.foundation` describes for atomic writes.
Removing a job is `config unset cron.watch-report` (or unset a single field);
unsetting the entity's last field deletes its file if it lives in one.

**How to verify.** `config get cron.watch-report` (or `handbook__read` topic
`config:cron.<name>.<field>` for one field's schema and current value, per
`isaac.foundation`'s `config:<dotted.path>` reference shape) shows what's
actually configured. `isaac config validate` catches a job whose `crew`
doesn't exist: it reports the bad value, the file, and the valid crew set.
Whether the job is actually scheduled is a separate question — see
Scheduling, below.

### Troubleshooting

- **A job you just wrote doesn't show up when you `config get` it.** Check
  it isn't split into `config/cron/<name>.md`/`.edn` under a name you didn't
  expect — `config get cron.<name>` reads the merged result regardless of
  which file holds it.
- **`config validate` flags `references undefined crew`.** The job's `crew`
  (or a session's crew, via `with-crew`) doesn't match any configured crew
  id. Fix the crew id or configure the crew member first.
- **A job addressed to a `comm` is refused at config-load time.** `comm` must
  be one of the comm names actually configured under `comms` — the error
  lists the valid set. A comm module has to be installed and configured
  before a job can target it.

## Prompt content

`prompt` is the one field every job must supply, and it can come from either
of two places: inline in the entity (`prompt` set directly), or from a
sibling markdown file, `config/cron/<name>.md`, with the file's body as the
prompt text — the same companion pattern as a crew's soul.

Unlike a crew's soul, a cron prompt's companion mode is **not** exclusive:
if both an inline `prompt` and a `cron/<name>.md` companion exist, the
inline value wins and Isaac logs `:config/companion-inline-wins` rather than
refusing the write. Only the missing-both and empty-companion cases are
config-load **errors**:

- neither inline `prompt` nor an existing `cron/<name>.md` → error,
  `required (inline or cron/<name>.md)`.
- `cron/<name>.md` exists but is empty → error, `must not be empty`.

A job's frontmatter can also carry every other field (`expr`, `crew`, ...)
with the body doubling as the prompt — a single `.md` file is a complete job.

**How to change it.** Inline: `config set cron.watch-report.prompt "File the
dawn watch for the bridge."`. Companion: write the prose directly to
`config/cron/watch-report.md` (no frontmatter needed if every other field is
already set elsewhere) — `handbook__configure` has no direct "write a file"
primitive for a bare companion body outside a full entity write, so getting
a companion-only prompt in place may need an operator's file edit `[verify]`.

**How to verify.** `config get cron.watch-report.prompt` shows the resolved
text regardless of which source it came from.

### Troubleshooting

- **A prompt edit doesn't seem to take effect.** If both an inline `prompt`
  and a companion `.md` exist, the inline value always wins — check
  `config get cron.<name>.prompt --raw` against the file you actually edited,
  and check the `cli`/`server` log stream for `:config/companion-inline-wins`
  to confirm which source is live.
- **Config load fails with `required (inline or cron/<name>.md)`.** Neither
  source is set. Set `prompt` inline, or create the companion file with
  content.
- **Config load fails with `must not be empty`.** The companion file exists
  but is blank — either fill it in or set `prompt` inline instead.

## Scheduling and timezone

A job's `expr` is a standard 5-field cron expression, evaluated in the
timezone from the root config's `tz` (falling back to the JVM's system
default if `tz` is unset) — there is no per-job timezone field; every job in
one Isaac install shares the same zone. Firing itself runs on
`isaac.foundation`'s shared scheduler: cron registers one `:cron`-trigger
task per job, id `cron/<job-name>`, when the server boots or whenever the
`:cron` config slice changes; removing a job from config cancels its task.

The scheduler polls roughly every 30 seconds `[verify]`. If a job's
scheduled fire is more than that behind the current time when the poll
catches it — the server was down, or badly delayed — the fire is **skipped
silently**, logged as `:cron/missed-schedule` at `warn`, not run late and not
queued.

**How to change it.** `config set cron.watch-report.expr "0 6 * * *"` for the
job's own schedule; `config set tz America/Chicago` for the shared zone (this
is a top-level root config key, not under `cron`).

**How to verify.** `handbook__read` topic `config:tz` shows the resolved
zone. Whether a task is actually registered is server-internal state — check
the `server` log stream (`isaac logs server`) around boot or a config change
for `:cron/missed-schedule` / any error from the job's turn, and confirm the
server process is actually running (`isaac.foundation` → Runtime: no CLI
command runs the scheduler).

### Troubleshooting

- **A job never seems to fire.** Confirm the server process is up — the
  scheduler only runs inside it. Then check `expr` and `tz` against when you
  expected it, and check the `server` log for `:cron/missed-schedule` (fired
  late enough to be skipped) versus no log at all (never reached that time,
  or the server was down through it).
- **A job fires at the wrong time.** Almost always `tz` — it's shared across
  every job in the install, not per-job. `config get tz` shows the current
  value; unset falls back to the JVM's own zone, which may not be what you
  expect on a given host.
- **A job that used to fire has gone quiet after a config edit.** Confirm
  the entry is still present and valid under `:cron` — `config validate`
  surfaces a schema problem that would otherwise just drop the task
  silently on the next reload.

## Session targeting

By default a cron job's `create` is `:always`: every fire spins up a brand
new session, tagged with structural origin `{:kind :cron :name <job-name>}`
so later queries can tell which cron produced it (`isaac sessions` lists
sessions; see `isaac.agent` for the session concept itself). Set `session`,
`session-tags`, `prefer`, and `create` to resume an existing session instead
— these are `isaac.agent`'s frequencies fields (`isaac.agent#frequencies`
has the shape and matching rules); this chapter only documents which of
cron's flat job fields map to which frequency, not that shared mechanism.

`with-crew`, `with-model`, `with-effort`, and `with-context-mode` override
the *scheduled turn* only — they don't change the job's own `crew` field or
persist onto the session.

**How to change it.** To resume the most recent session tagged for a job
instead of creating a new one each time:

```
config set cron.health-check.create if-missing
config set cron.health-check.prefer recent
```

**How to verify.** `config get cron.health-check` shows the frequency
fields as configured; which session actually got used is visible via
`isaac sessions` (session list) — its origin should show the job name.

### Troubleshooting

- **A job keeps writing to a fresh session instead of continuing the last
  one.** Check `create` — the cron default is `:always`, which always makes
  a new session regardless of history. Set `create if-missing` (or `never`)
  plus `session`/`session-tags`/`prefer` to target an existing one instead.
- **A job's turn ran under the wrong crew or model.** `with-crew`/
  `with-model`/etc. override the turn only for that fire; if a resumed
  session already has its own crew, that session's crew is used unless
  `with-crew` is set (an explicit override always wins over both the
  session's and the job's own `crew`).

## Delivering results

A job may name `comm` + `to` to route its reply out; both are optional
together, and a job with neither simply runs its turn with no delivery — the
reply lands only in the session's transcript. Delivery is queued, not sent
synchronously: cron enqueues `{comm, to, content}` after a successful turn,
and a separate delivery worker (owned by `isaac.agent`) sends it. The turn
itself always runs headless, through the null comm, regardless of `comm`/
`to` — those fields only control where the *finished reply* is delivered
afterward, not which comm the turn is attributed to while running.

A turn that errors, times out, or comes back with no assistant text enqueues
**no** delivery — see Job state, below, for how that outcome is recorded.

**How to change it.** `config set cron.watch-report.comm longwave` and
`config set cron.watch-report.to captain`; `config unset` either to stop
delivering and let the job run silently again.

**How to verify.** `config get cron.watch-report.comm` / `.to`. Whether a
delivery actually went out is the delivery worker's and the comm's own
concern, not cron's — check that comm's own chapter and log stream.

### Troubleshooting

- **A job's reply never reaches the comm.** First confirm the turn itself
  succeeded (see Job state, below) — a failed or empty turn enqueues nothing
  by design. If it succeeded, the delivery queue and the target comm are
  outside cron; check the delivery worker and the comm module's own chapter.
- **A job with no `comm`/`to` "loses" its output.** That's expected — an
  untargeted job's reply stays only in its session transcript, nothing is
  queued for delivery.

## Job state

Each job's last outcome is runtime **state**, not config: it lives at
`<root>/cron.edn`, one entry per job name, with `last-run` (the scheduled
time, not wall-clock fire time), `last-status` (`:succeeded` or `:failed`),
and `last-error` (nil on success; a short reason otherwise — a provider wall,
a provider error message, or `"empty assistant reply"` when the turn produced
no usable text). A later successful run clears a prior failure's error.
Being state, it's not reachable through `handbook__configure`/`config get`,
and whether a crew running inside Isaac can read `<root>/cron.edn` directly
depends on whether that path falls inside its allowed filesystem area
`[verify]`.

**How to verify.** There's no dedicated CLI command for cron state; the
`server` log stream records the same failures as they happen
(`:cron/job-failed` with `job`, `outcome`, and `message`) via `isaac logs
server`, which is reachable without touching the state file directly.

### Troubleshooting

- **A job's last-run doesn't match when you expected it to fire.** `last-run`
  records the *scheduled* time, not the moment the handler actually executed
  — a few seconds of poll latency is normal and not a bug.
- **`last-status` is `:failed` but the session shows an assistant reply.**
  Success requires a non-empty assistant reply with no error and no
  provider-unavailable condition — a technically-present but blank reply is
  still recorded as failed (`last-error` "empty assistant reply").
- **You need to see failure history for a job.** `cron.edn` only keeps the
  *last* outcome per job, not a log — for history, use the `server` log
  stream's `:cron/job-failed` entries instead.
