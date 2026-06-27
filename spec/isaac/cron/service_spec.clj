(ns isaac.cron.service-spec
  (:require
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.comm.delivery.queue :as delivery-queue]
    [isaac.comm.null :as null-comm]
    [isaac.llm.api.grover :as grover]

    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.cron.service :as sut]
    [isaac.fs :as fs]
    [isaac.scheduler.runtime :as scheduler-core]
    [isaac.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "cron scheduler"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (it)))

  (describe "CronModule lifecycle"

    (it "starts the scheduler on startup when cron jobs are present"
      (let [started (atom nil)
          module  (sut/make {:root "/test/isaac"})]
        (with-redefs [sut/start! (fn [opts]
                                   (reset! started opts)
                                   ::runner)]
          (runtime/on-load module {"health-check" {:expr "0 9 * * *"}})
          (should= {:cfg (or (loader/snapshot "spec") {}) :root "/test/isaac"}
                   @started))))

    (it "stops the old scheduler and restarts it when the slice changes"
      (let [started (atom [])
            stopped (atom [])
            module  (sut/make {:root "/test/isaac"})]
        (with-redefs [sut/start! (fn [opts]
                                   (swap! started conj opts)
                                   (keyword (str "runner-" (count @started))))
                      sut/stop!  (fn [runner]
                                   (swap! stopped conj runner))]
          (runtime/on-load module {"alpha" {:expr "0 9 * * *"}})
          (runtime/on-config-change! module
           {"alpha" {:expr "0 9 * * *"}}
           {"alpha" {:expr "0 10 * * *"}})
          (should= [:runner-1] @stopped)
          (should= 2 (count @started)))))

    (it "stops the scheduler when the slice is removed"
      (let [stopped (atom nil)
            module  (sut/make {:root "/test/isaac"})]
        (with-redefs [sut/start! (fn [_] ::runner)
                      sut/stop!  (fn [runner]
                                   (reset! stopped runner))]
          (runtime/on-load module {"alpha" {:expr "0 9 * * *"}})
          (runtime/on-config-change! module
           {"alpha" {:expr "0 9 * * *"}}
           nil)
          (should= ::runner @stopped))))

     (it "leaves the scheduler alone when the slice is unchanged"
       (let [started (atom 0)
             stopped (atom 0)
             module  (sut/make {:root "/test/isaac"})
             slice   {"alpha" {:expr "0 9 * * *"}}]
        (with-redefs [sut/start! (fn [_]
                                   (swap! started inc)
                                   ::runner)
                      sut/stop!  (fn [_]
                                   (swap! stopped inc))]
          (runtime/on-load module slice)
           (runtime/on-config-change! module slice slice)
           (should= 1 @started)
           (should= 0 @stopped)))))

  (it "registers one shared-scheduler task per cron job"
    (let [scheduled (atom [])
          fake-scheduler {}
          cfg {:tz "America/Chicago"
               :cron {"nightly-cleanup" {:expr "0 3 * * *" :crew "main" :prompt "tidy up"}
                      "heartbeat"       {:expr "*/5 * * * *" :crew "main" :prompt "ping"}}}]
      (nexus/register! [:scheduler] fake-scheduler)
      (with-redefs [scheduler-core/schedule! (fn [scheduler task]
                                               (swap! scheduled conj [scheduler (select-keys task [:id :trigger])])
                                               task)]
        (sut/start! {:cfg cfg :root "/test/isaac"}))
      (should= [[fake-scheduler {:id :cron/heartbeat :trigger {:kind :cron :expr "*/5 * * * *" :zone "America/Chicago"}}]
                [fake-scheduler {:id :cron/nightly-cleanup :trigger {:kind :cron :expr "0 3 * * *" :zone "America/Chicago"}}]]
               @scheduled)))

  (it "cancels registered cron tasks on stop"
    (let [cancelled (atom [])
          fake-scheduler {}]
      (with-redefs [scheduler-core/cancel! (fn [scheduler id]
                                             (swap! cancelled conj [scheduler id]))]
        (sut/stop! {:scheduler fake-scheduler :task-ids [:cron/nightly-cleanup :cron/heartbeat]}))
      (should= [[fake-scheduler :cron/nightly-cleanup]
                [fake-scheduler :cron/heartbeat]]
               @cancelled)))

  (it "stamps cron guidance on dispatched charges"
    (let [built   (atom nil)
          routed  (atom nil)
          session  {:id "session-1"}]
      (with-redefs [session-ctx/create-with-resolved-behavior! (fn [_ opts]
                                                                 (should= {:kind :cron :name "health-check"} (:origin opts))
                                                                 session)
                    charge/build                               (fn [request]
                                                                 (reset! built request)
                                                                 {:charge/type :charge})
                    bridge/dispatch!                           (fn [c]
                                                                 (reset! routed c)
                                                                 {})]
        (#'sut/fire-job! {:root "/test/isaac" :session-store (store/create "/test/isaac")}
                         {:defaults {:crew "main"}}
                         "health-check"
                         {:crew "main" :prompt "Run the health checkin."}
                         (java.time.ZonedDateTime/parse "2026-05-25T09:00:00-07:00[America/Phoenix]")))
      (should= {:kind :cron :name "health-check"} (:origin @built))
      (should= null-comm/channel (:comm @built))
      (should= "Scheduled cron turn; the user may not see your reply." (:guidance @built))
      (should= {:charge/type :charge} @routed)))

  (it "enqueues delivery when a cron job names a comm and recipient"
    (let [enqueued (atom nil)]
      (with-redefs [session-ctx/create-with-resolved-behavior! (fn [_ _] {:id "session-1"})
                    charge/build                               (fn [_] {:charge/type :charge})
                    bridge/dispatch!                           (fn [_] {:message {:content "Dawn watch clear."}})
                    delivery-queue/enqueue!                      (fn [record]
                                                                 (reset! enqueued record)
                                                                 record)]
        (#'sut/fire-job! {:root "/test/isaac" :session-store (store/create "/test/isaac")}
                         {:defaults {:crew "main"}}
                         "watch-report"
                         {:crew   "main"
                          :prompt "File the dawn watch."
                          :comm   "longwave"
                          :to     "captain"}
                         (java.time.ZonedDateTime/parse "2026-05-25T06:00:00-07:00[America/Phoenix]")))
      (should= {:comm    "longwave"
                :target  "captain"
                :to      "captain"
                :content "Dawn watch clear."}
               (select-keys @enqueued [:comm :target :to :content]))))

  (it "fire-job! enqueues delivery end-to-end with grover"
    (grover/install-test-fixture!)
    (grover/enqueue! [{:type "text" :content "Dawn watch clear." :model "echo"}])
    (nexus/register! [:sessions :store] (store/create "/test/isaac"))
    (let [cfg {:defaults {:crew "main" :model "grover"}
               :crew     {"main" {:model :grover :soul "You are Atticus."}}
               :models   {"grover" {:model "echo" :provider :grover :context-window 32768}}
               :providers {:grover {:api :grover :auth "none"}}}]
      (#'sut/fire-job! {:root "/test/isaac"}
                       cfg
                       "watch-report"
                       {:crew   "main"
                        :prompt "File the dawn watch."
                        :comm   "longwave"
                        :to     "captain"}
                       (java.time.ZonedDateTime/parse "2026-05-25T06:00:00-07:00[America/Phoenix]"))
      (should= 1 (count (delivery-queue/list-pending)))))

  (it "maybe-enqueue-delivery! writes a pending record for targeted jobs"
    (#'sut/maybe-enqueue-delivery! {:comm "longwave" :to "captain"}
                                   {:message {:content "Dawn watch clear."}})
    (should= 1 (count (delivery-queue/list-pending)))
    (should= {:comm    "longwave"
              :target  "captain"
              :to      "captain"
              :content "Dawn watch clear."}
             (select-keys (first (delivery-queue/list-pending))
                          [:comm :target :to :content])))

  (it "does not enqueue delivery for untargeted cron jobs"
    (let [enqueued (atom false)]
      (with-redefs [session-ctx/create-with-resolved-behavior! (fn [_ _] {:id "session-1"})
                    charge/build                               (fn [_] {:charge/type :charge})
                    bridge/dispatch!                           (fn [_] {:message {:content "Hull nominal."}})
                    delivery-queue/enqueue!                      (fn [_]
                                                                 (reset! enqueued true))]
        (#'sut/fire-job! {:root "/test/isaac" :session-store (store/create "/test/isaac")}
                         {:defaults {:crew "main"}}
                         "hull-check"
                         {:crew "main" :prompt "Tally the hull stress gauges."}
                         (java.time.ZonedDateTime/parse "2026-05-25T13:00:00-07:00[America/Phoenix]")))
      (should= false @enqueued))))
