(ns isaac.cron.state-spec
  (:require
    [isaac.cron.state :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "cron state"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "returns an empty state map when the file does not exist"
    (should= {} (sut/read-state)))

  (it "reads and writes explicit root paths"
    (sut/write-job-state! "/test/other-isaac" "heartbeat" {:last-status :succeeded})
    (should= {"heartbeat" {:last-status :succeeded}}
             (sut/read-state "/test/other-isaac")))

  (it "writes job status to cron.edn"
    (sut/write-job-state! "health-check" {:last-run    "2026-04-21T09:00:00-0500"
                                           :last-status :succeeded})
    (should= {"health-check" {:last-run    "2026-04-21T09:00:00-0500"
                               :last-status :succeeded}}
             (sut/read-state)))

  (it "merges updates into existing job state"
    (sut/write-job-state! "health-check" {:last-run    "2026-04-21T09:00:00-0500"
                                          :last-status :failed
                                          :last-error  "boom"})
    (sut/write-job-state! "health-check" {:last-status :succeeded
                                          :last-error  nil})
    (should= {"health-check" {:last-run    "2026-04-21T09:00:00-0500"
                                :last-status :succeeded
                                :last-error  nil}}
             (sut/read-state)))

  (it "uses the installed runtime fs without binding a thread-local fs"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:root "/test/isaac" :fs mem}
        (sut/write-job-state! "health-check" {:last-status :succeeded})
        (should= {"health-check" {:last-status :succeeded}}
                 (sut/read-state))))))
