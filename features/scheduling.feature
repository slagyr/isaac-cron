Feature: Cron jobs
  Isaac fires scheduled user prompts at cron-expression intervals.
  Each cron job specifies: name, cron expression, crew, and the prompt
  to send. When a job fires, it acts as if a user sent that prompt
  on behalf of the configured crew. The run's result is written back
  to <root>/cron.edn as last-run and last-status.

  Jobs are declared in root config under :cron {<name> {...}} — static
  intent, hand-editable, version-controllable. Runtime state lives in
  <root>/cron.edn separately.

  Standard 5-field cron (minute hour day month weekday) interpreted
  in the timezone from root config :tz (falling back to the JVM
  system default). Missed windows (while Isaac was down) are skipped
  silently with a warn log.

  Background:
    Given default Grover setup

  Scenario: a cron job fires at its schedule and runs a turn with its prompt
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | Run the health checkin. |
      | message | assistant    | Health is good.         |

  Scenario: a cron window missed while Isaac was down is skipped silently
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    When the scheduler ticks at "2026-04-21T11:30:00-0500"
    Then session "session-1" does not exist
    And the log has entries matching:
      | level | event                 | job          |
      | warn  | :cron/missed-schedule | health-check |

  Scenario: successful cron runs update the isaac file with last-run and last-status
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the isaac file "cron.edn" EDN contains:
      | path                     | value                    |
      | health-check.last-run    | 2026-04-21T09:00:00-0500 |
      | health-check.last-status | succeeded                |

  Scenario: cron jobs are registered as scheduler tasks
    Given the isaac EDN file "config/isaac.edn" contains:
      """
      {:tz "America/Chicago"
       :cron {:nightly-cleanup
              {:expr "0 3 * * *"
               :crew :main
               :prompt "tidy up"}}}
      """
    When the Isaac system is started
    Then the scheduled tasks include:
      | id                   | trigger.kind | trigger.expr | trigger.zone    |
      | cron/nightly-cleanup | cron         | 0 3 * * *    | America/Chicago |

  Scenario: removing a cron entry from config cancels its scheduler task
    Given the isaac EDN file "config/isaac.edn" contains:
      """
      {:tz "America/Chicago"
       :cron {:nightly-cleanup
              {:expr "0 3 * * *" :crew :main :prompt "tidy up"}}}
      """
    And the Isaac system is started
    When the isaac EDN file "config/isaac.edn" changes to:
      """
      {:tz "America/Chicago" :cron {}}
      """
    Then the scheduled tasks do not include "cron/nightly-cleanup"

  # --- isaac-7ngj: cron must not record success for a turn that failed ------
  # fire-job! derives :last-status from (:error result) only. A provider wall
  # (:unavailable? true, no :error) and other non-error failures therefore
  # write :succeeded. Success means: the turn ended with an assistant reply.

  @wip
  Scenario: a provider wall during a cron turn records failed with the reason (isaac-7ngj)
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | model | type       | status | retry-after |
      | echo  | http-error | 429    | 60          |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the isaac file "cron.edn" EDN contains:
      | path                     | value                       |
      | health-check.last-run    | 2026-04-21T09:00:00-0500    |
      | health-check.last-status | failed                      |
      | health-check.last-error  | #"(?i).*(wall|unavailable|429).*" |
    And the log has entries matching:
      | level | event            | job          | outcome      |
      | :warn | :cron/job-failed | health-check | :unavailable |

  @wip
  Scenario: a provider error during a cron turn records failed with the message (isaac-7ngj)
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | type  | content                 | model |
      | error | context length exceeded | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the isaac file "cron.edn" EDN contains:
      | path                     | value                          |
      | health-check.last-status | failed                         |
      | health-check.last-error  | #".*context length exceeded.*" |

  @wip
  Scenario: a cron turn that produces no assistant reply is not a success (isaac-7ngj)
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | type | content | model |
      | text |         | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the isaac file "cron.edn" EDN contains:
      | path                     | value                    |
      | health-check.last-status | failed                   |
      | health-check.last-error  | #"(?i).*empty.*"         |

  @wip
  Scenario: a successful cron run clears a previous failure (isaac-7ngj)
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the isaac EDN file "cron.edn" contains:
      | path                     | value                    |
      | health-check.last-run    | 2026-04-20T09:00:00-0500 |
      | health-check.last-status | failed                   |
      | health-check.last-error  | provider wall            |
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the isaac file "cron.edn" EDN contains:
      | path                     | value                    |
      | health-check.last-run    | 2026-04-21T09:00:00-0500 |
      | health-check.last-status | succeeded                |
      | health-check.last-error  | nil                      |
