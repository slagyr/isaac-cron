Feature: Cron config hot reload
  Cron jobs read the current on-disk config when a scheduled fire runs,
  so prompt changes written before the tick are picked up without a
  server restart.

  Background:
    Given default Grover setup

  Scenario: Cron prompt content change is picked up at runtime
    Given config:
      | tz                       | America/Chicago                         |
      | sessions.naming-strategy | sequential                              |
      | cron.evening-plan.expr   | 0 21 * * *                              |
      | cron.evening-plan.crew   | main                                    |
      | cron.evening-plan.prompt | What are we going to do tonight, Brain? |
    And the following model responses are queued:
      | type | content | model |
      | text | Narf!   | echo  |
    When cron config is:
      | cron.evening-plan.prompt | Same thing we do every night, Pinky — try to take over the world. |
    And the scheduler ticks at "2026-04-21T21:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content                                                    |
      | message | user         | Same thing we do every night, Pinky — try to take over the world. |
