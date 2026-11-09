(ns manager-svc.runs.sandbox-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.auth :as auth]
            [manager-svc.http :as http]
            [manager-svc.runs.sandbox :as sandbox]
            [manager-svc.test-support :as support]))

(def session-id #uuid "33333333-3333-3333-3333-333333333333")

(def harness
  {:llm {:connection "main" :models ["model-a"]} :mcp [:jenkins]})

(deftest sandboxes-reach-the-sandbox-listener-with-derived-keys
  (let [settings
        (assoc support/settings :sandbox-service-url "http://manager:8001")

        files
        (:files (sandbox/sandbox settings {:session-id session-id :agent-id "a" :agent {} :prompt "p"
                                           :harness harness :result-tool "submit_review" :password "pw"
                                           :skills {"lint/SKILL.md" "skill"}}))

        config
        (http/read-json (get files "opencode/opencode.json"))]
    (is
     (= {:url "http://manager:8001/mcp-gw/jenkins"
         :headers {:X-Platform-Key (auth/derived-key settings "tools" session-id)}}
        (select-keys (get-in config [:mcp :jenkins]) [:url :headers])))
    (is
     (= (auth/derived-key settings "llm" session-id)
        (get-in config [:provider :platform :options :apiKey])))
    (is
     (= session-id
        (auth/derived-key-owner settings (get-in config [:mcp :platform :headers :X-Platform-Key]) #{"mcp"})))
    (is
     (.contains ^String (get files "opencode/plugins/platform-monitor.js") "http://manager:8001/api/telemetry"))
    (is
     (= "skill"
        (get files "opencode/skills/lint/SKILL.md")))))

(deftest the-team-caps-the-idle-timeout
  (is
   (= [10 15 30 nil]
      [(sandbox/idle-minutes {:idle-minutes 15} {:budget {:idle-minutes 10}})
       (sandbox/idle-minutes {:idle-minutes 15} {:budget {:idle-minutes 30}})
       (sandbox/idle-minutes {} {:budget {:idle-minutes 30}})
       (sandbox/idle-minutes {} {})])))
