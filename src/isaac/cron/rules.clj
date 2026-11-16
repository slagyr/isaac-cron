(ns isaac.cron.rules
  (:require
    [isaac.foundation.scheduler.calendar :as calendar])
  (:import
    (java.time Duration Instant LocalDateTime ZoneId)))

(defn at-instant [at zone]
  (try
    (if (.endsWith ^String at "Z")
      (Instant/parse at)
      (.toInstant (.atZone (LocalDateTime/parse at) (ZoneId/of zone))))
    (catch Exception e
      (throw (ex-info (str "invalid :at: " at) {:at at} e)))))

(defn window [value]
  (try
    (let [duration (Duration/parse (or value "PT12H"))]
      (when (neg? (.toMillis duration)) (throw (ex-info "negative duration" {})))
      duration)
    (catch Exception e
      (throw (ex-info (str "invalid :late-window: " value) {:late-window value} e)))))

(defn validate-job! [job zone]
  (let [{:keys [at dtstart late-window]} job
        rule (:when job)]
    (when (= (boolean rule) (boolean at))
      (throw (ex-info "either :when or :at, not both" {})))
    (when rule (calendar/parse-rule rule dtstart))
    (when at (at-instant at zone))
    (when dtstart
      (try (LocalDateTime/parse dtstart)
           (catch Exception e (throw (ex-info (str "invalid :dtstart: " dtstart) {} e)))))
    (window late-window)
    job))

(defn trigger [job zone]
  (if-let [at (:at job)]
    {:kind :at :instant (at-instant at zone)}
    (cond-> {:kind :calendar :rule (:when job) :zone zone}
      (:dtstart job) (assoc :dtstart (:dtstart job)))))

(defn finite? [job]
  (or (:at job) (re-find #"(?:^|;)(?:COUNT|UNTIL)=" (or (:when job) ""))))

(defn next-fire [job zone now]
  (if-let [at (:at job)]
    (let [instant (at-instant at zone)]
      (when (.isAfter instant now) instant))
    (calendar/next-fire-at (:when job) (:dtstart job) zone now)))
