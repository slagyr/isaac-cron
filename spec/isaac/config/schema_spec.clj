(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [isaac.config.validation-lexicon :as validation-lexicon]
    [isaac.schema.lexicon :as lexicon]
    [isaac.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(defn- cron-schema []
  (-> "resources/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :cron :schema])))

(defn- cron-job-schema []
  (get-in (cron-schema) [:value-spec :schema]))

(def ^:private comm-module-index
  {:isaac.server {:manifest {:berths {:isaac.server/comm {:description "comms"}}}}
   :isaac.agent  {:manifest {:isaac.server/comm {:longwave {}
                                                  :skybeam  {}}}}})

(describe "config schema"

  (it "cron table conforms job maps"
    (binding [validation-lexicon/*config* {:crew {"main" {}}}]
      (should= {"health-check" {:expr "0 9 * * *"
                                :crew "main"
                                :prompt "Run the health checkin."}}
               (lexicon/conform (cron-schema)
                                {"health-check" {:expr "0 9 * * *"
                                                 :crew :main
                                                 :prompt "Run the health checkin."}}))))

  (it "accepts a cron job addressed to a registered comm"
    (binding [registered-in/*module-index* comm-module-index
              registered-in/*config*         {:comms {"longwave" {}}}]
      (should-be-nil
        (-> (schema/conform {:comm (:comm (cron-job-schema))} {:comm "longwave"})
            :comm schema/error-message))))

  (it "rejects a cron job targeting an unknown comm"
    (binding [registered-in/*module-index* comm-module-index
              registered-in/*config*         {:comms {"longwave" {}}}]
      (should= "must be one of [\"longwave\" \"skybeam\"]"
               (-> (schema/conform {:comm (:comm (cron-job-schema))} {:comm "nope"})
                   :comm schema/error-message)))))
