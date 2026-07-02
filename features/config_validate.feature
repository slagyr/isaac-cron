Feature: Config Validate (cron entities)
  `isaac config validate` checks cron entity files under config/cron/ against
  the :cron schema contributed by this module, reporting references to crew
  members that are not defined in the resolved config.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: validate reports unknown crew refs with file and valid set
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "cron/nightly.edn" containing:
      """
      {:expr "0 9 * * *" :crew :ghost :prompt "Ping"}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                               |
      | cron\.nightly\.crew                 |
      | references undefined crew           |
      | file: config/cron/nightly\.edn       |
      | bad value: ghost                     |
      | valid: .*main.*                      |
    And the exit code is 1
