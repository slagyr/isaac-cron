Feature: Cron-spawned session origin
  Sessions spawned by cron record structural origin data so later
  queries can identify which cron produced them.

  Background:
    Given default Grover setup

  Scenario: cron-spawned session carries origin pointing at the cron
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
    Then the following sessions match:
      | id        | origin.kind | origin.name  |
      | session-1 | cron        | health-check |
