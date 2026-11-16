Feature: Cron rules — :when (RRULE), :at (one-shot), finite jobs leave
  A cron job says when it runs with :when, an iCalendar RRULE in the
  install :tz (foundation's :calendar trigger), or :at, a one-shot local
  datetime; exactly one of the two. :dtstart pins an RRULE's phase. :expr
  is gone. A finite job — an :at, or a rule with COUNT/UNTIL — leaves the
  config after its last run (config unset through the mutate path); its
  last definition and outcome stay in <root>/cron.edn under :history. A
  missed :at still runs late within :late-window (ISO-8601 duration,
  default PT12H); past it, it is skipped, removed and logged. `isaac cron
  list` shows each job's rule and next run. (isaac-e0qe)

  Background:
    Given default Grover setup

  Scenario: a :when rule registers as a calendar task in the install zone
    Given the isaac EDN file "config/isaac.edn" contains:
      """
      {:tz "America/Chicago"
       :cron {:standup {:when "FREQ=WEEKLY;BYDAY=TU,TH;BYHOUR=9;BYMINUTE=0"
                        :crew :main :prompt "Stand-up notes."}}}
      """
    When the Isaac system is started
    Then the scheduled tasks include:
      | id           | trigger.kind | trigger.rule                                | trigger.zone    |
      | cron/standup | calendar     | FREQ=WEEKLY;BYDAY=TU,TH;BYHOUR=9;BYMINUTE=0 | America/Chicago |

  Scenario: a one-shot :at job runs once, leaves the config, and keeps its record
    Given config:
      | tz                       | America/Chicago |
      | sessions.naming-strategy | sequential      |
    And config file "cron/reminder.edn" containing:
      """
      {:at "2026-04-21T09:00:00" :crew :main :prompt "Call the harbor master."}
      """
    And the following model responses are queued:
      | type | content      | model |
      | text | Calling now. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | Call the harbor master. |
      | message | assistant    | Calling now.            |
    And the isaac file "config/cron/reminder.edn" does not exist
    And the isaac file "cron.edn" EDN contains:
      | path                            | value                    |
      | history.reminder.at             | 2026-04-21T09:00:00      |
      | history.reminder.prompt         | Call the harbor master.  |
      | history.reminder.last-status    | succeeded                |
      | history.reminder.removed-at     | 2026-04-21T09:00:00-0500 |

  Scenario: a rule whose UNTIL has passed leaves the config after its last run
    Given config:
      | tz                       | America/Chicago |
      | sessions.naming-strategy | sequential      |
    And config file "cron/sprint.edn" containing:
      """
      {:when "FREQ=DAILY;BYHOUR=9;BYMINUTE=0;UNTIL=20260421T090000" :crew :main :prompt "Sprint check."}
      """
    And the following model responses are queued:
      | type | content    | model |
      | text | On track.  | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the isaac file "config/cron/sprint.edn" does not exist
    And the isaac file "cron.edn" EDN contains:
      | path                         | value     |
      | history.sprint.last-status   | succeeded |

  Scenario: a missed one-shot runs late within :late-window
    Given config:
      | tz                       | America/Chicago |
      | sessions.naming-strategy | sequential      |
    And config file "cron/reminder.edn" containing:
      """
      {:at "2026-04-21T09:00:00" :crew :main :prompt "Call the harbor master."}
      """
    And the following model responses are queued:
      | type | content      | model |
      | text | Calling now. | echo  |
    When the scheduler ticks at "2026-04-21T11:30:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | Call the harbor master. |
    And the isaac file "config/cron/reminder.edn" does not exist

  Scenario: a one-shot missed by more than :late-window is skipped, removed and logged
    Given config:
      | tz                       | America/Chicago |
      | sessions.naming-strategy | sequential      |
    And config file "cron/reminder.edn" containing:
      """
      {:at "2026-04-21T09:00:00" :late-window "PT2H" :crew :main :prompt "Call the harbor master."}
      """
    When the scheduler ticks at "2026-04-21T11:30:00-0500"
    Then session "session-1" does not exist
    And the isaac file "config/cron/reminder.edn" does not exist
    And the log has entries matching:
      | level | event                  | job      |
      | :warn | :cron/one-shot-expired | reminder |
    And the isaac file "cron.edn" EDN contains:
      | path                         | value   |
      | history.reminder.last-status | expired |

  Scenario: a bad rule is an error on its own job; the other jobs still schedule
    Given the isaac EDN file "config/isaac.edn" contains:
      """
      {:tz "America/Chicago"
       :cron {:standup {:when "FREQ=WEEKLY;BYDAY=TU,TH;BYHOUR=9;BYMINUTE=0" :crew :main :prompt "Stand-up notes."}
              :payday  {:when "FREQ=WEEKLY;INTERVAL=2;BYDAY=FR;BYHOUR=9" :crew :main :prompt "Pay day."}}}
      """
    When the Isaac system is started
    Then the scheduled tasks include "cron/standup"
    And the scheduled tasks do not include "cron/payday"
    And the log has entries matching:
      | level  | event                    | key         |
      | :error | :config/validation-error | cron.payday |

  Scenario: config validate names the job and the bad part
    Given an Isaac root at "isaac-state"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :local}}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "cron/payday.edn" containing:
      """
      {:when "FREQ=WEEKLY;INTERVAL=2;BYDAY=FR;BYHOUR=9" :crew :main :prompt "Pay day."}
      """
    And config file "cron/both.edn" containing:
      """
      {:when "FREQ=DAILY;BYHOUR=9" :at "2026-04-21T09:00:00" :crew :main :prompt "Hm."}
      """
    And config file "cron/legacy.edn" containing:
      """
      {:expr "0 9 * * *" :crew :main :prompt "Old style."}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                       |
      | cron\.payday\.when.*INTERVAL > 1 requires :dtstart |
      | cron\.both.*either :when or :at, not both     |
      | cron\.legacy\.expr                            |
    And the exit code is 1

  Scenario: cron list lines up its columns to the longest name and rule (isaac-y3go)
    Given the isaac EDN file "config/isaac.edn" contains:
      """
      {:tz "America/Chicago"
       :cron {:standup            {:when "FREQ=WEEKLY;BYDAY=TU,TH;BYHOUR=9;BYMINUTE=0" :crew :main :prompt "Stand-up notes."}
              :reminder           {:at "2026-11-20T09:00:00" :crew :main :prompt "Call the harbor master."}
              :tempest-vault-sync {:when "FREQ=DAILY;BYHOUR=0;BYMINUTE=0" :crew :main :prompt "Sync the vault."}}}
      """
    And the clock is fixed at "2026-11-16T08:00:00-06:00"
    When isaac is run with "cron list"
    Then the stdout contains "NAME                WHEN                                         NEXT"
    And the stdout contains "reminder            at 2026-11-20T09:00:00                       2026-11-20 09:00 CST"
    And the stdout contains "standup             FREQ=WEEKLY;BYDAY=TU,TH;BYHOUR=9;BYMINUTE=0  2026-11-17 09:00 CST"
    And the stdout contains "tempest-vault-sync  FREQ=DAILY;BYHOUR=0;BYMINUTE=0               2026-11-17 00:00 CST"
    And the exit code is 0

  Scenario: cron list with no jobs says so (isaac-y3go)
    Given the isaac EDN file "config/isaac.edn" contains:
      """
      {:tz "America/Chicago"}
      """
    When isaac is run with "cron list"
    Then the stdout contains "No cron jobs."
    And the exit code is 0
