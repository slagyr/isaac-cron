Feature: Cron session frequencies
  A cron job builds a session-frequencies map from its flat config
  (crew / session / session-tags / create / prefer alongside the :with-*
  overrides) and resolves it through the shared isaac.session.frequencies
  core. This lets a scheduled prompt resume the most-recent matching session
  (or always start fresh) and override crew/model/effort/context-mode for the
  turn — instead of always creating a brand-new session per tick.

  Background:
    Given default Grover setup

  Scenario: a cron job resumes the most-recent matching session
    Given config:
      | key                      | value                   |
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
      | cron.health-check.create | if-missing              |
      | cron.health-check.prefer | recent                  |
    And the following sessions exist:
      | name       | crew | updated-at           |
      | morning    | main | 2026-04-20T08:00:00Z |
      | last-night | main | 2026-04-21T02:00:00Z |
    And session "last-night" has transcript:
      | type    | message.role | message.content |
      | message | user         | Prior note.     |
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "last-night" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | Prior note.             |
      | message | user         | Run the health checkin. |
      | message | assistant    | Health is good.         |
    And session "morning" has transcript matching:
      | type    | message.role | message.content |

  Scenario: a cron job's with-model override flows to the scheduled turn
    Given config:
      | key                          | value                   |
      | tz                           | America/Chicago         |
      | sessions.naming-strategy     | sequential              |
      | cron.health-check.expr       | 0 9 * * *               |
      | cron.health-check.crew       | main                    |
      | cron.health-check.prompt     | Run the health checkin. |
      | cron.health-check.with-model | grover2                 |
    And the isaac EDN file "config/models/grover2.edn" exists with:
      | path           | value    |
      | model          | echo-alt |
      | provider       | grover   |
      | context-window | 16384    |
    And the following model responses are queued:
      | type | content         | model    |
      | text | Health is good. | echo-alt |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then session "session-1" has transcript matching:
      | type    | message.role | message.content         |
      | message | user         | Run the health checkin. |
      | message | assistant    | Health is good.         |
