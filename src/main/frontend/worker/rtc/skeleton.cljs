(ns frontend.worker.rtc.skeleton
  "Validate skeleton data between server and client"
  (:require [clojure.data :as data]
            [datascript.core :as d]
            [frontend.worker.rtc.ws-util :as ws-util]
            [frontend.worker.util :as worker-util]
            [missionary.core :as m]))

(defn- get-schema-version
  [db]
  (:kv/value (d/entity db :logseq.kv/schema-version)))

(defn- get-builtin-db-idents
  [db]
  (d/q '[:find [?i ...]
         :in $
         :where
         [?b :db/ident ?i]
         [?b :block/uuid]
         [?b :logseq.property/built-in?]]
       db))

(defn new-task--calibrate-graph-skeleton
  [get-ws-create-task graph-uuid db]
  (m/sp
    (let [r (m/? (ws-util/send&recv get-ws-create-task
                                    {:action "get-graph-skeleton"
                                     :graph-uuid graph-uuid}))]
      (if-let [remote-ex (:ex-data r)]
        (case (:type remote-ex)
          :graph-lock-failed
          (throw (ex-info "retry calibrate-graph-skeleton" {:missionary/retry true}))
          ;; else
          (do (prn {:remote-ex remote-ex})
              (throw (ex-info "Unavailable2" {:remote-ex remote-ex}))))
        (let [{:keys [server-schema-version server-builtin-db-idents]} r
              client-builtin-db-idents (set (get-builtin-db-idents db))
              client-schema-version (get-schema-version db)]
          (when (not= client-schema-version server-schema-version)
            (worker-util/post-message :notification
                                      [[:div
                                        [:p (str :client-schema-version client-schema-version)]
                                        [:p (str :server-schema-version server-schema-version)]]
                                       :error]))
          (let [[client-only server-only _]
                (data/diff client-builtin-db-idents server-builtin-db-idents)]
            (when (or (seq client-only) (seq server-only))
              (worker-util/post-message :notification
                                        [(cond-> [:div]
                                           (seq client-only)
                                           (conj [:p (str :client-only-db-idents client-only)])
                                           (seq server-only)
                                           (conj [:p (str :server-only-db-idents server-only)]))
                                         :error]))))))))
