(ns isaac.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [isaac.config.validation-lexicon :as validation-lexicon]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer :all]))

(defn- cron-schema []
  (-> "resources/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :cron :schema])))

(describe "config schema"

  (it "cron table conforms job maps"
    (binding [validation-lexicon/*config* {:crew {"main" {}}}]
      (should= {"health-check" {:expr "0 9 * * *"
                                :crew "main"
                                :prompt "Run the health checkin."}}
               (lexicon/conform (cron-schema)
                                {"health-check" {:expr "0 9 * * *"
                                                 :crew :main
                                                 :prompt "Run the health checkin."}})))))
