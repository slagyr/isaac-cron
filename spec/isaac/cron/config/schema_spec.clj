(ns isaac.cron.config.schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [isaac.foundation.config.validation-lexicon :as validation-lexicon]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.schema.lexicon :as lexicon]
    [isaac.foundation.schema.registered-in :as registered-in]
    [speclj.core :refer :all]))

(defn- cron-schema []
  (-> "resources/isaac-manifest.edn"
      slurp
      edn/read-string
      (get-in [:isaac.config/schema :cron :schema])))

(defn- cron-job-schema []
  (get-in (cron-schema) [:value-spec :schema]))

(def ^:private comm-module-index
  {:isaac.agent         {:manifest {:berths {:isaac.agent/comm {:description "comms"}}}}
   :isaac.comm.longwave {:manifest {:isaac.agent/comm {:longwave {}
                                                       :skybeam  {}}}}})

(describe "config schema"

  ;; :crew-exists? is contributed by isaac-agent through the
  ;; :isaac.config/validation-ref berth (isaac-h2oo), not a foundation
  ;; static ref, so it isn't in the global lexicon until something composes
  ;; the module index at least once. Register it explicitly rather than
  ;; relying on another spec file (e.g. handbook_chapter_spec) happening to
  ;; run first and populate it as a side effect.
  (before-all
    (nexus/-with-nexus {:fs (fs/real-fs)}
      (validation-lexicon/register-contributed-existence-refs! (discovery/builtin-index))))

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
