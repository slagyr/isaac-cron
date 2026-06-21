@wip
Feature: Cron delivery to a comm
  A cron entry may address a comm + recipient; its session output is
  delivered through that comm. With no comm target, output is discarded
  (the skybeam / null comm).

  Background:
    Given default Grover setup

  Scenario: a cron addressed to a comm delivers its report through that comm
    Given config:
      | cron.watch-report.expr   | 0 6 * * *                           |
      | cron.watch-report.crew   | main                                |
      | cron.watch-report.prompt | File the dawn watch for the bridge. |
      | cron.watch-report.comm   | longwave                            |
      | cron.watch-report.to     | captain                             |
    And the following model responses are queued:
      | type | content                               | model |
      | text | Dawn watch clear; Marigold steady on. | echo  |
    When the scheduler ticks at "2026-04-21T06:00:00-0500"
    And the delivery worker ticks
    Then the comm "longwave" was called with:
      | to      | content                               |
      | captain | Dawn watch clear; Marigold steady on. |

  Scenario: an untargeted cron runs but enqueues no delivery (skybeam/null default)
    Given config:
      | cron.hull-check.expr   | 0 13 * * *                    |
      | cron.hull-check.crew   | main                          |
      | cron.hull-check.prompt | Tally the hull stress gauges. |
    And the following model responses are queued:
      | type | content                       | model |
      | text | Hull nominal; rivets holding. | echo  |
    When the scheduler ticks at "2026-04-21T13:00:00-0500"
    Then the following sessions match:
      | origin.kind | origin.name |
      | cron        | hull-check  |
    And the delivery queue is empty
