(ns isaac.cron.checks
  (:require
        [isaac.cron.rules :as rules]))

(def ^:private allowed-keys
  #{:when :at :dtstart :late-window :crew :prompt :comm :to :session :session-tags
    :prefer :create :with-crew :with-model :with-effort :with-context-mode :id})

(defn check-jobs [{:keys [config result]}]
  (let [jobs (merge (get-in result [:root :cron]) (get-in result [:raw :cron]) (:cron config))
        zone (or (:tz config) (str (java.time.ZoneId/systemDefault)))]
    {:errors (vec
               (mapcat (fn [[name job]]
                         (let [prefix (str "cron." (if (keyword? name) (clojure.core/name name) name))
                               unknown (for [key (keys job) :when (not (allowed-keys key))]
                                         {:key (str prefix "." (clojure.core/name key)) :value "unknown key"})
                               invalid (try (rules/validate-job! job zone) nil
                                            (catch Exception e
                                              [{:key (str prefix (when (:when job) ".when"))
                                                :value (.getMessage e)}]))]
                           (concat unknown invalid)))
                       jobs))
     :warnings []}))
