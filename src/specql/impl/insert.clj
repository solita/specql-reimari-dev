(ns specql.impl.insert
  "Implementation of insert! and upsert!"
  (:require [clojure.spec.alpha :as s]
            [specql.impl.util :refer :all]
            [specql.impl.composite :as composite]
            [specql.impl.registry :as registry]
            [specql.impl.where :as where]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]))

;; Remove this when 1.9 is out
(when-not (resolve 'any?)
  (require '[clojure.future :refer :all]))



(defn- primary-key-columns [columns]
  (into {}
        (keep (fn [[kw {pk? :primary-key?}]]
                (when pk?
                  [kw [kw]]))
              columns)))

(defn insert!
  [db table-kw record]
  (assert-table table-kw)
  (let [table-info-registry @registry/table-info-registry
        {table-name :name columns :columns
         insert-spec :insert-spec-kw :as table}
        (table-info-registry table-kw)]
    (assert-spec insert-spec record)
    (let [record (transform-to-sql table-info-registry table record)
          primary-key-columns (primary-key-columns columns)
          alias-fn (gen-alias)
          alias (alias-fn :ins)
          cols (when-not (empty? primary-key-columns)
                 (fetch-columns table-info-registry table-kw alias alias-fn primary-key-columns))
          [column-names value-names value-parameters]
          (columns-and-values-to-set table-info-registry table-kw record)

          sql (str "INSERT INTO \"" table-name "\" AS " alias " ("
                   (str/join ", " (map #(str "\"" % "\"") column-names)) ") "
                   "VALUES (" (str/join "," value-names) ") "
                   (when cols
                     (str "RETURNING " (sql-columns-list cols))))
          sql-and-params (into [sql] value-parameters)]
      ;;(println "SQL: " (pr-str sql-and-params))
      (if (empty? primary-key-columns)
        (do (jdbc/execute! db sql-and-params)
            record)
        (let [result (first (jdbc/query db sql-and-params))]
          (reduce (fn [record [resultset-kw [_ output-kw]]]
                    (assoc-in record output-kw (result resultset-kw)))
                  record
                  cols))))))

(s/def ::keyset-record-where
  (s/cat :keyset (s/? (s/and (s/coll-of keyword?)
                             set?))
         :record (s/map-of keyword? any?)
         :where (s/? (s/map-of keyword? any?))))


(defn upsert! [db table & keyset-record-where]
  (assert-table table)
  (let [table-info-registry @registry/table-info-registry
        {table-name :name table-columns :columns
         insert-spec :insert-spec-kw :as table-info}
        (table-info-registry table)

        {:keys [keyset record where]}
        (s/conform ::keyset-record-where keyset-record-where)

        original-record (assert-spec insert-spec record)
        record (transform-to-sql table-info-registry
                                 table-info
                                 original-record)

        primary-keys (primary-key-columns table-columns)
        conflict-keys (or keyset
                          (keys primary-keys))
        conflict-target (map (comp :name table-columns)
                             conflict-keys)
        conflict-target-column? (into #{} conflict-target)

        _ (assert (not (empty? conflict-target))
                  (str "No conflict target, if table has no primary key, specify a column set"))
        _ (assert (every? string? conflict-target)
                  (str "Unknown columns in conflict target " (pr-str conflict-keys)))

        ;; If a keyset was provided, make sure all keys are in the input record.
        ;; Dont check for primary keys (they are often autogenerated serials)
        _ (assert (or (nil? keyset)
                      (every? (partial contains? record) conflict-keys))
                  (str "Conflict target contains columns that are not in the record to upsert: "
                       (pr-str (into #{}
                                     (remove (partial contains? record))
                                     conflict-keys))))
        alias-fn (gen-alias)
        alias (alias-fn table)

        [column-names value-names value-parameters]
        (columns-and-values-to-set table-info-registry table record)

        [where-clause where-parameters]
        (where/sql-where table-info-registry
                         #(when (= % [])
                            {:table table
                             :alias alias})
                         where)



        cols (when-not (empty? primary-keys)
               (fetch-columns table-info-registry table alias alias-fn primary-keys))
        sql (str "INSERT INTO \"" table-name "\" AS " alias " "
                 "(" (str/join "," (map q column-names)) ") "
                 "VALUES (" (str/join "," value-names) ") "
                 "ON CONFLICT (" (str/join "," (map q conflict-target)) ") "
                 "DO UPDATE SET " (str/join ","
                                            (keep #(when-not (conflict-target-column? %)
                                                     (str (q %) " = EXCLUDED." (q %)))
                                                  column-names))
                 (when-not (str/blank? where-clause)
                   (str " WHERE "  where-clause))
                 (when cols
                   (str " RETURNING " (sql-columns-list cols))))

        sql-and-params (into [sql]
                             (concat value-parameters where-parameters))]

    ;;(println "SQL: " (pr-str sql-and-params))
    (if (empty? primary-keys)
      ;; No returning clause, execute and check affected rows count
      (if (zero? (first (jdbc/execute! db sql-and-params)))
        nil
        original-record)

      ;; Returning primary keys, do query and add them to record
      (let [result (first (jdbc/query db sql-and-params))]
        (when result
          (reduce (fn [record [resultset-kw [_ output-kw]]]
                    (assoc-in record output-kw (result resultset-kw)))
                  original-record
                  cols))))))
