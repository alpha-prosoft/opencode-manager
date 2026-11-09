(ns manager-web.components.session-actions-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [manager-web.components.session-actions :as session-actions]))

(def me
  {:tenant "acme" :sub "bob" :teams [{:id "ops" :name "Operations" :role :member :claimed-role :member}]})

(deftest a-session-command-refreshes-what-the-page-shows
  (is
   (= {:id [:stop-session 1]
       :error :shown-here
       :command {:cmd-id :stop-session :id 1}
       :refetch [:sessions]}
      (session-actions/command-spec :stop-session 1 {:error-id :shown-here :refetch [:sessions]}))))

(deftest a-fork-opens-the-copy-and-reads-the-credentials-again-when-refused
  (is
   (= {:id [:fork-session 1]
       :error :shown-here
       :command {:cmd-id :fork-session :id 2 :attrs {:source-id 1}}
       :refetch-on-failure [:my-credentials]
       :navigate [:session {:session-id "2"}]}
      (session-actions/fork-spec 1 2 :shown-here))))

(deftest forking-says-where-the-copy-lives-and-whose-credentials-it-uses
  (let [body
        (session-actions/fork-body me {:name "Fix login" :team-id "ops"})]
    (is
     (true? (string/includes? body "It stays in Operations and is private to you.")))
    (is
     (true? (string/includes? body "your own where set, else Operations', else acme's")))))

(deftest the-fork-request-runs-the-fork
  (let [request
        (session-actions/fork-request me {:id 1 :name "Fix login" :team-id "ops"} :shown-here)]
    (is
     (= ["Fork session" "primary" :fork-session]
        [(:confirm request) (:color request) (get-in request [:run :command :cmd-id])]))))
