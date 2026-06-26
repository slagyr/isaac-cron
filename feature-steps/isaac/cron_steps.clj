(ns isaac.cron-steps
  "Cron-specific gherclj steps. Foundation scheduler steps live in
   isaac.scheduler-steps from isaac-foundation-spec."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen after-scenario helper!]]
    [isaac.config.loader :as loader]
    [isaac.cron.service :as cron-service]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.cron :as cron]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.server.app :as app]
    [isaac.session.store.spi :as store]
    [isaac.spec-helper :as helper])
  (:import
    (java.time ZonedDateTime)
    (java.time.format DateTimeFormatter)))

(helper! isaac.cron-steps)

(def ^:private offset-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn- current-scheduler []
  (or (g/get :scheduler)
      (nexus/get :scheduler)))

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

(defn- stop-cron-scenario! []
  (when-let [runner (g/get :cron-runner)]
    (cron-service/stop! runner))
  (when-let [instance (current-scheduler)]
    (scheduler/stop! instance))
  (app/stop!)
  (nexus/reset!)
  (log/clear-entries!))

(after-scenario stop-cron-scenario!)

(defn scheduler-ticks-at [iso]
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

(defn isaac-system-started []
  (log/set-output! :memory)
  (log/clear-entries!)
  (g/assoc! :bind-server-port? false)
  (g/assoc! :runtime-root-dir (g/get :root))
  (let [fs*        (cron-server-fs)
        root       (g/get :root)
        cfg        (merge (load-cron-server-config root fs*)
                          (when-let [providers (g/get :provider-configs)]
                            {:providers providers}))
        scheduler* (-> (scheduler/create {}) scheduler/start!)]
    (nexus/install! {:config    (atom cfg)
                     :scheduler scheduler*
                     :root      root
                     :fs        fs*
                     :sessions  {:store (store/create root)}})
    (g/assoc! :scheduler scheduler*)
    (g/assoc! :cron-runner (cron-service/start! {:cfg cfg :root root}))))

(defn isaac-system-stopped []
  (app/stop!))

(defn- isaac-root-path []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- cron-isaac-file-path [path]
  (cond
    (str/starts-with? path "/") path
    (= path "isaac.edn") (str (isaac-root-path) "/config/isaac.edn")
    (str/starts-with? path "config/") (str (isaac-root-path) "/" path)
    :else (str (isaac-root-path) "/" path)))

(defn- reload-cron-after-config-change! [file-path]
  (when-let [runner (g/get :cron-runner)]
    (let [fs*  (cron-server-fs)
          root (g/get :root)
          cfg  (merge (load-cron-server-config root fs*)
                      (when-let [providers (g/get :provider-configs)]
                        {:providers providers}))]
      (cron-service/stop! runner)
      (when-let [cfg-atom (nexus/get :config)]
        (reset! cfg-atom cfg))
      (g/assoc! :cron-runner (cron-service/start! {:cfg cfg :root root}))
      (log/debug :cron/config-reloaded :path file-path))))

(defn isaac-edn-file-contains-content [path content]
  (with-cron-server-fs
    (fn []
      (let [file-path (cron-isaac-file-path path)
            fs*       (cron-server-fs)]
        (fs/mkdirs fs* (fs/parent file-path))
        (fs/spit fs* file-path (str/trim content))
        (reload-cron-after-config-change! file-path)))))

(defn config-applied [table]
  ((requiring-resolve 'isaac.foundation.harness-config-steps/config-applied) table))

(defn cron-config-is [table]
  (config-applied table))

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

(defwhen #"the scheduler ticks at \"([^\"]+)\"" isaac.cron-steps/scheduler-ticks-at
  "Schedules configured cron jobs on the shared scheduler, then invokes
   their registered handlers at the given ISO timestamp.")

(defgiven #"the isaac EDN file \"([^\"]+)\" contains:" isaac.cron-steps/isaac-edn-file-contains-content
  "Writes heredoc EDN under the Isaac root and reloads cron when the system is running.")

(defwhen #"the isaac EDN file \"([^\"]+)\" changes to:" isaac.cron-steps/isaac-edn-file-contains-content
  "Alias for the heredoc EDN writer used after startup to simulate hot reload.")

(defwhen "the Isaac system is started" isaac.cron-steps/isaac-system-started)

(defwhen "the Isaac system is stopped" isaac.cron-steps/isaac-system-stopped)

(defwhen "cron config is:" isaac.cron-steps/cron-config-is
  "Applies a config table update before a scheduler tick so cron jobs
   observe the rewritten prompt on the next fire.")

(defthen "the scheduler is running" isaac.cron-steps/scheduler-running)

(defthen "the scheduler is not running" isaac.cron-steps/scheduler-not-running)

(defthen "the cron job {string} has:" isaac.cron-steps/cron-job-has)