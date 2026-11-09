(ns manager-svc.observability.usage-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [edd.security :as security]
            [edd.test.fixture.dal :as mock]
            [manager-svc.observability.core :as core]
            [manager-svc.observability.metrics :as metrics]
            [manager-svc.observability.usage :as usage]
            [manager-svc.platform.api :as platform]
            [manager-svc.test-support :as support]
            [manager-svc.time :as time]))

(def session-a #uuid "6f1c5a43-2222-4a52-9c1b-6a3f0b6a0001")
(def session-b #uuid "6f1c5a43-2222-4a52-9c1b-6a3f0b6a0002")
(def run-id #uuid "6f1c5a43-2222-4a52-9c1b-6a3f0b6a00ff")

(def session-c #uuid "6f1c5a43-2222-4a52-9c1b-6a3f0b6a0003")

(def spec
  {:llm-connections [{:id "openai" :pricing {:gpt-4o {:input 2.0 :output 10.0 :cached-input 1.0}}}]})

(defn- ctx
  []
  (security/register (core/register (support/ctx))))

(defn- record!
  [ctx session-id call]
  (mock/apply-cmd (support/as-platform ctx)
                  {:commands (usage/commands (merge {:session-id (str session-id)
                                                     :tenant "acme"
                                                     :team-id "alpha"
                                                     :connection "openai"
                                                     :model "gpt-4o"
                                                     :status 200
                                                     :ts (time/now)}
                                                    call))}))

(defn- seed!
  [ctx]
  (record! ctx session-a {:run-id (str run-id) :prompt-tokens 1000 :completion-tokens 200 :cached-tokens 400 :latency-ms 100})
  (record! ctx session-a {:run-id (str run-id) :prompt-tokens 500 :completion-tokens 50 :latency-ms 300})
  (record! ctx session-a {:run-id (str run-id) :status 500 :latency-ms 20})
  (record! ctx session-b {:team-id "beta" :model "llama" :connection "local" :prompt-tokens 10 :completion-tokens 5})
  (record! ctx session-c {:tenant "globex" :team-id "alpha" :username "gina" :prompt-tokens 7000 :completion-tokens 70}))

(deftest session-totals-accumulate
  (mock/with-mock-dal
    (let [ctx
          (ctx)

          _
          (seed! ctx)]
      (is
       (= {:calls 3 :prompt-tokens 1500 :completion-tokens 250 :cached-tokens 400 :errors 1 :avg-latency-ms 140}
          (mock/query (support/as-platform ctx) {:query-id :get-usage-summary :session-id (str session-a)})))
      (is
       (= 3
          (:calls (mock/query (support/as-platform ctx) {:query-id :get-usage-summary :run-id (str run-id)})))))))

(deftest team-usage-counts-since
  (mock/with-mock-dal
    (let [ctx
          (support/as-platform (ctx))

          _
          (seed! ctx)

          today
          (mock/query ctx {:query-id :get-usage-summary :tenant "acme" :team-id "alpha" :since (time/start-of-day)})

          tomorrow
          (mock/query ctx {:query-id :get-usage-summary :tenant "acme" :team-id "alpha" :since "2999-01-01T00:00:00Z"})]
      (is
       (= 1750
          (+ (:prompt-tokens today) (:completion-tokens today))))
      (is
       (= 0
          (:calls tomorrow))))))

(deftest rows-group-by-team-connection-model
  (mock/with-mock-dal
    (let [ctx
          (support/as-platform (ctx))

          _
          (seed! ctx)]
      (is
       (= [{:tenant "acme" :team-id "alpha" :connection "openai" :model "gpt-4o" :calls 3 :prompt-tokens 1500
            :completion-tokens 250 :cached-tokens 400 :errors 1 :cost nil}]
          (vec (mock/query ctx {:query-id :get-usage-rows :tenant "acme" :team-ids ["alpha"] :since (time/start-of-day)}))))
      (is
       (= [["globex" "alpha" 7070]]
          (mapv (juxt :tenant :team-id #(+ (:prompt-tokens %) (:completion-tokens %)))
                (mock/query ctx {:query-id :get-usage-rows :tenant "globex" :since (time/start-of-day)})))))))

(defn- spec-of
  [_system tenant connection-id]
  (when (= "acme" tenant)
    (some #(when (= connection-id (:id %)) %) (:llm-connections spec))))

(deftest admin-usage-prices-the-rows-of-the-callers-tenant
  (with-redefs [platform/llm-connection spec-of]
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (seed! ctx)

            rows
            (mock/query (support/as-user ctx "root" {:tenant-admin true}) {:query-id :get-admin-usage :days 7})]
        (is
         (= [["acme" "alpha" "gpt-4o" 0.0051] ["acme" "beta" "llama" nil]]
            (mapv (juxt :tenant :team-id :model :cost) rows)))
        (is
         (= 403
            (try
              (mock/query (support/as-user ctx "alice") {:query-id :get-admin-usage :days 7})
              nil
              (catch Exception e
                (:status (ex-data e))))))))))

(deftest a-tenant-admin-never-sees-another-tenants-usage
  (with-redefs [platform/llm-connection spec-of]
    (mock/with-mock-dal
      (let [ctx
            (ctx)

            _
            (seed! ctx)

            rows
            (mock/query (support/as-user ctx "gina" {:tenant "globex" :tenant-admin true})
                        {:query-id :get-admin-usage :days 7})]
        (is
         (= [["globex" "alpha" 7000 nil]]
            (mapv (juxt :tenant :team-id :prompt-tokens :cost) rows)))
        (is
         (= {:tokens 1750}
            (mock/query (support/as-platform ctx) {:query-id :get-team-tokens-today :tenant "acme" :team-id "alpha"})))
        (is
         (= {:tokens 7070}
            (mock/query (support/as-platform ctx) {:query-id :get-team-tokens-today :tenant "globex" :team-id "alpha"})))
        (is
         (= [{:username "gina" :tokens 7070}]
            (mock/query (support/as-platform ctx) {:query-id :get-usage-by-user :tenant "globex" :since (time/start-of-day)})))))))

(deftest metrics-read-the-all-time-rollups
  (mock/with-mock-dal
    (let [ctx
          (support/as-platform (ctx))

          _
          (seed! ctx)

          text
          (metrics/render (mock/query ctx {:query-id :get-observability-metrics}) 2)]
      (is
       (string/includes? text "opencode_llm_tokens_total{tenant=\"acme\",team=\"alpha\",model=\"gpt-4o\",type=\"prompt\"} 1500"))
      (is
       (string/includes? text "opencode_llm_tokens_total{tenant=\"globex\",team=\"alpha\",model=\"gpt-4o\",type=\"prompt\"} 7000"))
      (is
       (string/includes? text "opencode_llm_errors_total{tenant=\"acme\",team=\"alpha\",model=\"gpt-4o\"} 1"))
      (is
       (string/includes? text "opencode_sessions_running 2")))))

(deftest price-uses-input-rate-for-cached-tokens-without-cached-price
  (is
   (= 0.003
      (usage/price {:id "c" :pricing {:m {:input 1.0 :output 2.0}}}
                   {:connection "c" :model "m" :prompt-tokens 1000 :cached-tokens 500 :completion-tokens 1000})))
  (is
   (nil?
    (usage/price nil {:connection "c" :model "m" :prompt-tokens 1}))))

(deftest a-team-page-reads-its-usage-in-one-query
  (mock/with-mock-dal
    (let [ctx
          (support/as-platform (ctx))]
      (seed! ctx)
      (is
       (= {:usage {:calls 3 :prompt-tokens 1500 :completion-tokens 250 :cached-tokens 400 :errors 1 :avg-latency-ms 140}
           :used-today 1750
           :by-model [{:model "gpt-4o" :calls 3 :prompt-tokens 1500 :completion-tokens 250}]}
          (mock/query ctx {:query-id :get-team-usage :tenant "acme" :team-id "alpha" :since (time/start-of-day)}))))))

(deftest the-budget-reads-the-team-total-of-today
  (mock/with-mock-dal
    (let [ctx
          (support/as-platform (ctx))]
      (seed! ctx)
      (is
       (= {:tokens 1750}
          (mock/query ctx {:query-id :get-team-tokens-today :tenant "acme" :team-id "alpha"})))
      (is
       (= {:tokens 15}
          (mock/query ctx {:query-id :get-team-tokens-today :tenant "acme" :team-id "beta"})))
      (is
       (= {:tokens 0}
          (mock/query ctx {:query-id :get-team-tokens-today :tenant "acme" :team-id "gamma"}))))))

(deftest a-window-of-n-days-covers-n-days-including-today
  (is
   (= [(subs (time/start-of-day) 0 10)]
      (usage/days-since (usage/since-days 1))))
  (is
   (= 7
      (count (usage/days-since (usage/since-days 7))))))

(deftest tokens-are-attributed-to-the-session-owner
  (mock/with-mock-dal
    (let [ctx
          (support/as-platform (ctx))]
      (record! ctx session-a {:username "alice" :prompt-tokens 100 :completion-tokens 20})
      (record! ctx session-a {:username "alice" :prompt-tokens 10 :completion-tokens 5})
      (record! ctx session-b {:username "bob" :team-id "beta" :prompt-tokens 1 :completion-tokens 1})
      (record! ctx session-b {:prompt-tokens 999})
      (is
       (= [{:username "alice" :tokens 135} {:username "bob" :tokens 2}]
          (mock/query ctx {:query-id :get-usage-by-user :tenant "acme" :since (time/start-of-day)})))
      (is
       (= [{:tenant "acme" :team-id "alpha" :connection "openai" :model "gpt-4o" :calls 3 :prompt-tokens 1109
            :completion-tokens 25 :cached-tokens 0 :errors 0 :cost nil}]
          (vec (mock/query ctx {:query-id :get-usage-rows :tenant "acme" :team-ids ["alpha"] :since (time/start-of-day)})))))))

(deftest metrics-families-are-contiguous-and-labels-escaped
  (let [text
        (metrics/render {:usage [{:team-id "a\"b" :model "m\\n" :calls 1 :errors 0 :prompt-tokens 1 :completion-tokens 1}
                                 {:team-id "c" :model "m" :calls 2 :errors 1 :prompt-tokens 1 :completion-tokens 1}]
                         :tools [{:team-id "c" :tool "bash" :kind :tool.blocked :n 3}]}
                        0)

        lines
        (string/split-lines text)

        family-of
        (fn [line] (second (re-find #"^(?:# (?:HELP|TYPE) )?([a-z_]+)" line)))

        families
        (map first (partition-by identity (map family-of lines)))]
    (is
     (= (distinct families)
        families))
    (is
     (some #{"# TYPE opencode_llm_errors_total counter"} lines))
    (is
     (some #{"# TYPE opencode_tool_blocked_total counter"} lines))
    (is
     (string/includes? text "team=\"a\\\"b\",model=\"m\\\\n\""))))
