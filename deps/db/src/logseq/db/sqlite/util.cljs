(ns logseq.db.sqlite.util
  "Utils fns for backend sqlite db"
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [clojure.string :as string]
            [cognitect.transit :as transit]
            [datascript.core :as d]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.frontend.property :as db-property]))

(defn- type-of-block
  "
  TODO: use :block/type
  | value | meaning                                        |
  |-------+------------------------------------------------|
  |     1 | normal block                                   |
  |     2 | page block                                     |
  |     3 | init data, (config.edn, custom.js, custom.css) |
  |     4 | db schema                                      |
  |     5 | unknown type                                   |
  |     6 | property block                                 |
  "
  [block]
  (cond
    (:block/page block) 1
    (some #{:file/content :schema/version :db/type} (keys block)) 3
    (contains? (:block/type block) "property") 6
    (:block/name block) 2
    :else 5))

(defn time-ms
  "Copy of util/time-ms. Too basic to couple this to main app"
  []
  (tc/to-long (t/now)))

(defn ds->sqlite-block
  "Convert a datascript block to a sqlite map in preparation for a sqlite-db fn"
  [b]
  {:uuid (str (:block/uuid b))
   :type (type-of-block b)
   :page_uuid (str (:page_uuid b))
   :page_journal_day (:block/journal-day b)
   :name (or (:file/path b) (:block/name b))
   :content (or (:file/content b) (:block/content b))
   :datoms (:datoms b)
   :created_at (or (:block/created-at b) (time-ms))
   :updated_at (or (:block/updated-at b) (time-ms))})

(defn block-map->datoms-str
  "Given a block map and all existing blocks, return the block as transit data
   to be stored in the `datoms` column. This is currently only used in testing"
  [blocks m]
  (let [t-writer (transit/writer :json)]
    (->> (dissoc m :db/id)
         ;; This fn should match pipeline/datom->av-vector
         (map (fn m->av-vector [[a v]]
                [a v]
                (cond
                  (contains? db-schema/card-one-ref-type-attributes a)
                  [a [:block/uuid (str (some #(when (= (:db/id %) (:db/id v)) (:block/uuid %)) blocks))]]

                  (contains? db-schema/card-many-ref-type-attributes a)
                  [a (seq
                      (map (fn [{db-id :db/id}]
                             [:block/uuid (some #(when (= db-id (:db/id %)) (:block/uuid %)) blocks)])
                           v))]
                  :else [a v])))
         (transit/write t-writer))))

(defn block-with-timestamps
  "Copy of outliner-core/block-with-timestamps. Too basic to couple this to main app"
  [block]
  (let [updated-at (time-ms)
        block (cond->
               (assoc block :block/updated-at updated-at)
                (nil? (:block/created-at block))
                (assoc :block/created-at updated-at))]
    block))

(defn sanitize-page-name
  "Prepares a string for insertion to :block/name. Not using
  gp-util/page-name-sanity-lc yet because it's unclear if db graphs have all the
  same naming constraints"
  [s]
  (string/lower-case s))

(defn build-new-property
  "Build a standard new property so that it is is consistent across contexts"
  [block]
  (block-with-timestamps
   (merge {:block/type "property"
           :block/journal? false
           :block/format :markdown}
          block)))

(defn build-db-initial-data
  [config-content]
  (let [initial-files [{:block/uuid (d/squuid)
                        :file/path (str "logseq/" "config.edn")
                        :file/content config-content}
                       {:block/uuid (d/squuid)
                        :file/path (str "logseq/" "custom.css")
                        :file/content ""}
                       {:block/uuid (d/squuid)
                        :file/path (str "logseq/" "custom.js")
                        :file/content ""}]
        default-properties (map
                            (fn [[k-keyword {:keys [schema original-name]}]]
                              (let [k-name (name k-keyword)]
                                (build-new-property
                                 {:block/schema schema
                                  :block/original-name (or original-name k-name)
                                  :block/name (sanitize-page-name k-name)
                                  :block/uuid (d/squuid)})))
                            db-property/built-in-properties)]
    (concat initial-files default-properties)))
