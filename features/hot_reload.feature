Feature: Cron config hot reload
  A running config harness updates cron job state when the config
  slice changes at runtime.

  Background:
    Given default Grover setup

  @wip
  Scenario: Cron prompt content change is picked up at runtime
    Given the isaac config path "cron.evening-plan.expr" is "0 21 * * *"
    And the isaac config path "sessions.naming-strategy" is "sequential"
    And the isaac config path "cron.evening-plan.crew" is "main"
    And the isaac config path "cron.evening-plan.prompt" is "What are we going to do tonight, Brain?"
    And the Isaac config harness is started
    And the following model responses are queued:
      | type | content | model |
      | text | Narf!   | echo  |
    When the isaac config path "cron.evening-plan.prompt" is "Same thing we do every night, Pinky — try to take over the world."
    And the scheduler ticks at "2026-04-21T21:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content                                                    |
      | message | user         | Same thing we do every night, Pinky — try to take over the world. |
