(ns manager-web.guide
  "Regenerates the screenshot user guides under docs/guide by driving the
   running platform (MANAGER_URL, default http://localhost:8080 in oidc mode):

     cd web && clojure -M:guide                  every guide
     cd web && clojure -M:guide member operator  only these

   On the documentation stack (make docs-stack, scripted LLM) it first plays
   the seeded cast (manager-web.guide-seed), once per stack, so every figure
   shows the same people, runs and results on each regeneration.

   A guide is published only when it completed; a failed one keeps its
   previous page and leaves target/guide/img/FAILED-<prefix>.png."
  (:require [manager-web.guide-doc :as doc]
            [manager-web.guide-seed :as seed]
            [manager-web.guide-support :as s]
            [manager-web.member-guide :as member]
            [manager-web.operator-guide :as operator]
            [manager-web.team-admin-guide :as team-admin]
            [manager-web.tenant-admin-guide :as tenant-admin]
            [testing.pw :as pw]))

(def guides
  [["member" member/guide member/guide!]
   ["team-admin" team-admin/guide team-admin/guide!]
   ["tenant-admin" tenant-admin/guide tenant-admin/guide!]
   ["operator" operator/guide operator/guide!]])

(defn- chosen
  [names]
  (if (seq names)
    (filter (fn [[guide-name]] (contains? (set names) guide-name)) guides)
    guides))

(defn- clean-up!
  "Removes what the guides created, after the last one: later guides show
   what earlier ones started."
  []
  (try
    (.setViewportSize (s/page) 1440 900)
    (team-admin/clean-up!)
    (member/clean-up!)
    (catch Exception e
      (println "Could not clean up after the guides:" (ex-message e)))))

(defn -main
  [& names]
  (println "The guides expect a clean documentation stack (make docs-stack); they also run on a used one.")
  (let [browser
        (s/start-browser)

        started
        (System/currentTimeMillis)

        results
        (try
          (binding [pw/*current-page* (:page browser)]
            (try
              (seed/ensure-seeded!)
              (doall
               (for [[guide-name guide body-fn] (chosen names)]
                 [guide-name (doc/run-guide! guide body-fn)]))
              (finally
                (clean-up!))))
          (finally
            (s/stop-browser browser)))]
    (println "\nGuides:" (pr-str results) "in" (quot (- (System/currentTimeMillis) started) 1000) "s")
    (shutdown-agents)
    (System/exit (if (every? second results) 0 1))))
