(ns manager-svc.migrations-test
  (:require [clojure.test :refer [deftest is]]
            [edd.view-store.postgres.api :as view-store]
            [honey.sql :as sql]
            [manager-svc.migrations :as migrations]))

(deftest the-view-store-schema-is-the-one-edd-queries
  (is
   (= (str "SELECT * FROM " (migrations/view-store-schema :manager-svc) ".aggregates")
      (first (sql/format {:select [:*] :from [(view-store/->table migrations/realm :manager-svc)]})))))
