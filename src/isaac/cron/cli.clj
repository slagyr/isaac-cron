(ns isaac.cron.cli
  (:require
    [isaac.agent.tool.memory :as memory]
    [isaac.cron.rules :as rules]
    [isaac.foundation.cli.api :as cli]
    [clojure.string :as str])
  (:import
    (java.time ZoneId ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(def ^:private time-format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm z"))

(defn list-jobs [cfg now]
  (let [zone (or (:tz cfg) (str (ZoneId/systemDefault)))]
    (for [[name job] (sort-by (comp str key) (:cron cfg))]
      (let [label (if-let [rule (:when job)] rule (str "at " (:at job)))
            next-run (rules/next-fire job zone now)]
        (str (format "%-10s" (clojure.core/name name)) label
             (apply str (repeat (if (:at job) 26 3) " ")) "next "
             (if next-run (.format (ZonedDateTime/ofInstant next-run (ZoneId/of zone)) time-format) "none"))))))

(defmethod cli/run :cron [_ opts]
  (if (= ["list"] (vec (:_raw-args opts)))
    (let [cfg (:config opts)]
      (doseq [row (list-jobs cfg (memory/now))] (println row))
      0)
    (do (binding [*out* *err*] (println "Usage: isaac cron list")) 1)))

(defmethod cli/option-spec :cron [_] [])
(defmethod cli/subcommands :cron [_] [{:name "list" :summary "List jobs and next run"}])
