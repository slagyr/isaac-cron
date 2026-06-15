(ns isaac.scheduler-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.config.loader :as loader]
    [isaac.cron.service :as cron-service]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.scheduler.cron :as cron]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.server.app :as app]
    [isaac.session.store.spi :as store]
    [isaac.spec-helper :as helper]
    [isaac.step-tables :as match]
    [isaac.nexus :as nexus])
  (:import
    (java.time Instant OffsetDateTime ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(helper! isaac.scheduler-steps)

(defn- unquote-string [value]
  (when-not (nil? value)
    (let [value (str value)]
      (if (and (<= 2 (count value))
               (str/starts-with? value "\"")
               (str/ends-with? value "\""))
        (subs value 1 (dec (count value)))
        value))))

(defn- task-id->string [id]
  (if-let [ns (namespace id)]
    (str ns "/" (name id))
    (name id)))

(defn- cell-value [value]
  (let [value (some-> value unquote-string)]
    (when-not (str/blank? value)
      value)))

(defn- present-task [task]
  (assoc task :id (task-id->string (:id task))))

(defn- parse-instant [value]
  (try
    (Instant/parse (unquote-string value))
    (catch Exception _
      (.toInstant (OffsetDateTime/parse (unquote-string value))))))

(defn- parse-duration-ms [value]
  (let [value (unquote-string value)]
    (cond
    (nil? value) nil
    (re-matches #"\d+ms" value) (parse-long (subs value 0 (- (count value) 2)))
    (re-matches #"\d+s" value) (* 1000 (parse-long (subs value 0 (dec (count value)))))
    (re-matches #"\d+" value) (parse-long value)
    :else (throw (ex-info "unsupported duration" {:value value})))))

(defn- bool-value [value]
  (= "true" (str/lower-case (str value))))

(defn- handler-counts* []
  (or (g/get :scheduler-handler-counts*)
      (let [counts* (atom {})]
        (g/assoc! :scheduler-handler-counts* counts*)
        counts*)))

(defn- handler-count [id]
  (get @(handler-counts*) id 0))

(defn- current-scheduler []
  (or (g/get :scheduler)
      (nexus/get :scheduler)))

(defn- task-row->task [table]
  (let [row     (zipmap (:headers table) (first (:rows table)))
        id      (keyword (cell-value (get row "id")))
        runtime (parse-duration-ms (cell-value (get row "handler-runtime")))
        throws? (bool-value (cell-value (get row "handler-throws")))
        counts* (handler-counts*)]
    {:id            id
     :trigger       (cond-> {:kind (keyword (cell-value (get row "trigger.kind")))}
                      (cell-value (get row "trigger.ms"))      (assoc :ms (parse-duration-ms (cell-value (get row "trigger.ms"))))
                      (cell-value (get row "trigger.expr"))    (assoc :expr (cell-value (get row "trigger.expr")))
                      (cell-value (get row "trigger.zone"))    (assoc :zone (cell-value (get row "trigger.zone")))
                      (cell-value (get row "trigger.instant")) (assoc :instant (cell-value (get row "trigger.instant"))))
     :coalesce       (some-> (cell-value (get row "coalesce")) keyword)
     :on-error       (some-> (cell-value (get row "on-error")) keyword)
     :backoff-ms     (some-> (cell-value (get row "backoff-ms")) parse-duration-ms)
     :max-backoff-ms (some-> (cell-value (get row "max-backoff-ms")) parse-duration-ms)
     :retry-attempts (some-> (cell-value (get row "retry-attempts")) parse-long)
     :timeout-ms     (some-> (cell-value (get row "timeout-ms")) parse-duration-ms)
     :handler       (fn [_]
                      (swap! counts* update (name id) (fnil inc 0))
                      (when runtime
                        (Thread/sleep runtime)) ; intentional: simulates handler execution time for timeout/coalesce scenarios
                      (when throws?
                        (throw (ex-info "scheduler handler failed" {:id id}))))}))

(defn- start-scheduler! [iso]
  (let [clock*   (atom (parse-instant iso))
        instance (-> (scheduler/create {:clock (fn [] @clock*)})
                     scheduler/start!)]
    (log/set-output! :memory)
    (log/clear-entries!)
    (g/assoc! :scheduler-clock* clock*)
    (g/assoc! :scheduler instance)
    (nexus/reset!)
    (nexus/init!)
    (nexus/register! [:scheduler] instance)))

(defn- stop-scheduler! []
  (when-let [runner (g/get :cron-runner)]
    (cron-service/stop! runner))
  (when-let [instance (current-scheduler)]
    (scheduler/stop! instance))
  (app/stop!)
  (nexus/reset!)
  (log/clear-entries!))

(g/after-scenario stop-scheduler!)

(defn- advance-clock! [duration]
  (when-let [clock* (g/get :scheduler-clock*)]
    (swap! clock* #(.plusMillis ^Instant % (parse-duration-ms duration)))))

(defn- set-clock! [iso]
  (when-let [clock* (g/get :scheduler-clock*)]
    (reset! clock* (parse-instant iso))))

(defn scheduler-started [iso]
  (start-scheduler! iso))

(defn scheduled-task [table]
  (scheduler/schedule! (current-scheduler) (task-row->task table)))

(defn clock-advances [duration]
  (advance-clock! duration)
  (scheduler/tick! (current-scheduler)))

(defn clock-advances-and-settles [duration]
  (clock-advances duration))

(defn clock-advances-to [iso]
  (set-clock! iso)
  (scheduler/tick! (current-scheduler)))

(defn scheduler-ticks []
  (scheduler/tick! (current-scheduler)))

(def ^:private offset-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn- cron-server-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-cron-server-fs [f]
  (nexus/-with-nested-nexus {:fs (cron-server-fs)} (f)))

(defn- load-cron-server-config [root fs*]
  (let [load!       #(:config (loader/load-config-result {:root root :fs fs*}))
        entity-dir? #(with-cron-server-fs
                       (fn []
                         (seq (fs/children fs* (str root "/config/" %)))))
        cfg         (load!)]
    (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
             (empty? (or (:crew cfg) {}))
             (empty? (or (:models cfg) {}))
             (empty? (or (:providers cfg) {})))
      (load!)
      cfg)))

(defn- scheduler-idle? [instance]
  (every? (fn [task]
            (and (nil? (:active-run task))
                 (empty? (:pending-fire-ats task))))
          (scheduler/list-tasks instance)))

(defn- invoke-scheduled-cron-tasks! [scheduler now]
  (doseq [{:keys [handler trigger]} (scheduler/list-tasks scheduler)]
    (let [zone         (java.time.ZoneId/of (or (:zone trigger) (str (.getZone now))))
          scheduled-at (cron/previous-fire-at (:expr trigger) now zone)]
      (when scheduled-at
        (handler {:scheduled-at (.toInstant scheduled-at)
                  :now          (.toInstant now)})))))

(defn scheduler-ticks-at [iso]
  (g/assoc! :isaac-file-phase :assert)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-cron-server-fs
    (fn []
      (let [fs*        (cron-server-fs)
            root       (g/get :root)
            cfg        (merge (load-cron-server-config root fs*)
                              (when-let [providers (g/get :provider-configs)]
                                {:providers providers}))
            now        (ZonedDateTime/parse iso offset-formatter)
            scheduler* (scheduler/create {:clock (fn [] (.toInstant now))})]
        (try
          (nexus/-with-nexus {:config    (atom cfg)
                              :scheduler scheduler*
                              :root      root
                              :fs        fs*
                              :sessions  {:store (store/create root)}}
            (let [runner (cron-service/start! {:cfg cfg :root root})]
              (try
                (invoke-scheduled-cron-tasks! scheduler* now)
                (helper/await-condition #(scheduler-idle? scheduler*) 3000)
                (finally
                  (cron-service/stop! runner)))))
          (finally
            (scheduler/shutdown! scheduler*)))))))

(defn scheduler-stops []
  (scheduler/stop! (current-scheduler)))

(defn scheduler-shuts-down []
  (scheduler/shutdown! (current-scheduler)))

(defn ask-for-scheduled-tasks []
  (g/assoc! :scheduled-tasks (scheduler/list-tasks (current-scheduler))))

(defn cancel-task [id]
  (scheduler/cancel! (current-scheduler) (keyword (unquote-string id))))

(defn attempt-schedule-task [table]
  (g/assoc! :scheduler-error
            (try
              (scheduler/schedule! (current-scheduler) (task-row->task table))
              nil
              (catch clojure.lang.ExceptionInfo e
                e))))

(defn handler-fired [id count]
  (let [id    (unquote-string id)
        count (parse-long (str count))]
    (helper/await-condition #(= count (handler-count id)) 3000)
    (g/should= count (handler-count id))))

(defn handler-did-not-fire [id]
  (g/should= 0 (handler-count (unquote-string id))))

(defn scheduled-tasks-include [table]
  (let [result* (atom nil)]
    (helper/await-condition
      (fn []
        (let [tasks  (mapv present-task (scheduler/list-tasks (current-scheduler)))
              result (match/match-entries table tasks)]
          (reset! result* result)
          (empty? (:failures result))))
      3000)
    (g/should= [] (:failures @result*))))

(defn scheduled-tasks-do-not-include [id]
  (let [id (unquote-string id)]
    (helper/await-condition
      #(not (some (fn [task] (= id (:id task)))
                  (map present-task (scheduler/list-tasks (current-scheduler)))))
      3000)
    (g/should-not (some #(= id (:id %)) (map present-task (scheduler/list-tasks (current-scheduler)))))))

(defn scheduled-tasks-are-empty []
  (helper/await-condition #(empty? (scheduler/list-tasks (current-scheduler))) 3000)
  (g/should= [] (scheduler/list-tasks (current-scheduler))))

(defn no-error-is-logged []
  (g/should-not (some #(= :error (:level %)) (log/get-entries))))

(defn error-raised [pattern]
  (let [message (some-> (g/get :scheduler-error) .getMessage)]
    (g/should (some? message))
    (g/should (re-find (re-pattern (unquote-string pattern)) message))))

(defn- parse-state-value [value]
  (cond
    (re-matches #"-?\d+" value)        (parse-long value)
    (= "true" (str/lower-case value))  true
    (= "false" (str/lower-case value)) false
    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")) (edn/read-string value)
    :else value))

(defn- get-by-dotted-path [m path]
  (get-in m (mapv keyword (str/split path #"\."))))

(defn isaac-system-started []
  (log/set-output! :memory)
  (log/clear-entries!)
  (g/assoc! :bind-server-port? false)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-cron-server-fs
    (fn []
      (let [fs*        (cron-server-fs)
            root       (g/get :root)
            cfg        (merge (load-cron-server-config root fs*)
                              (when-let [providers (g/get :provider-configs)]
                                {:providers providers}))
            scheduler* (-> (scheduler/create {}) scheduler/start!)]
        (g/assoc! :scheduler scheduler*)
        (nexus/-with-nexus {:config    (atom cfg)
                            :scheduler scheduler*
                            :root      root
                            :fs        fs*
                            :sessions  {:store (store/create root)}}
          (let [runner (cron-service/start! {:cfg cfg :root root})]
            (g/assoc! :cron-runner runner)))))))

(defn isaac-system-stopped []
  (app/stop!))

(defn cron-job-has [name table]
  (let [result* (atom nil)]
    (helper/await-condition
      (fn []
        (when-let [instance (nexus/get-in [:cron])]
          (when-let [state (cron-service/job-state instance name)]
            (let [rows  (map #(zipmap (:headers table) %) (:rows table))
                  fails (keep (fn [row]
                                (let [path     (get row "path")
                                      expected (parse-state-value (get row "value"))
                                      actual   (get-by-dotted-path state path)]
                                  (when-not (= expected actual)
                                    [path expected actual])))
                              rows)]
              (reset! result* fails)
              (empty? fails)))))
      3000)
    (g/should= [] @result*)))

(defn scheduler-running []
  (g/should (scheduler/running? (current-scheduler))))

(defn scheduler-not-running []
  (g/should-not (scheduler/running? (current-scheduler))))

(defgiven "the scheduler is started with the clock at {string}" isaac.scheduler-steps/scheduler-started)

(defgiven "a scheduled task:" isaac.scheduler-steps/scheduled-task)

(defwhen "the clock advances {string}" isaac.scheduler-steps/clock-advances)

(defwhen "the clock advances {string} and pending handlers complete" isaac.scheduler-steps/clock-advances-and-settles)

(defwhen "the clock advances to {string}" isaac.scheduler-steps/clock-advances-to)

(defwhen "the scheduler ticks" isaac.scheduler-steps/scheduler-ticks)

(defwhen #"the scheduler ticks at \"([^\"]+)\"" isaac.scheduler-steps/scheduler-ticks-at
  "Schedules configured cron jobs on the shared scheduler, then invokes
   their registered handlers at the given ISO timestamp.")

(defwhen "the scheduler stops" isaac.scheduler-steps/scheduler-stops)

(defwhen "the scheduler shuts down" isaac.scheduler-steps/scheduler-shuts-down)

(defwhen "I ask for the scheduled tasks" isaac.scheduler-steps/ask-for-scheduled-tasks)

(defwhen "I cancel {string}" isaac.scheduler-steps/cancel-task)

(defwhen "I attempt to schedule a task:" isaac.scheduler-steps/attempt-schedule-task)

(defwhen "the Isaac system is started" isaac.scheduler-steps/isaac-system-started)

(defwhen "the Isaac system is stopped" isaac.scheduler-steps/isaac-system-stopped)

(defthen "handler {string} has fired {int} times" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} has fired {int} time" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} started {int} times" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} started {int} time" isaac.scheduler-steps/handler-fired)

(defthen "handler {string} has not fired" isaac.scheduler-steps/handler-did-not-fire)

(defthen "the scheduled tasks include:" isaac.scheduler-steps/scheduled-tasks-include)

(defthen "the scheduled tasks do not include {string}" isaac.scheduler-steps/scheduled-tasks-do-not-include)

(defn scheduled-tasks-include-id [id]
  (let [id (unquote-string id)]
    (helper/await-condition
      #(some (fn [task] (= id (:id task)))
             (map present-task (scheduler/list-tasks (current-scheduler))))
      3000)))

(defthen "the scheduled tasks include {string}" isaac.scheduler-steps/scheduled-tasks-include-id)

(defthen "the scheduled tasks are empty" isaac.scheduler-steps/scheduled-tasks-are-empty)

(defthen "no error is logged" isaac.scheduler-steps/no-error-is-logged)

(defthen "an error is raised with message matching {string}" isaac.scheduler-steps/error-raised)

(defthen #"an error is raised with message matching \"([^\"]+)\"" isaac.scheduler-steps/error-raised)

(defthen "the scheduler is running" isaac.scheduler-steps/scheduler-running)

(defthen "the scheduler is not running" isaac.scheduler-steps/scheduler-not-running)

(defthen "the cron job {string} has:" isaac.scheduler-steps/cron-job-has)
