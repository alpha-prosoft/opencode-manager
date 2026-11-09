(ns manager-web.urls-test
  (:require [clojure.test :refer [deftest is]]
            [manager-web.urls :as urls]))

(deftest safe-next-keeps-local-paths
  (doseq [path ["/" "/runs" "/runs/1?tab=x" "/sessions/bob/1234" "/teams/demo"]]
    (is
     (= path
        (urls/safe-next path)))))

(deftest safe-next-sends-everything-else-home
  (doseq [path [nil "" "runs" "https://evil.example" "//evil.example" "/\\evil.example" "/\\/evil.example"
                "/\tevil" "/\n/evil.example" "/ evil" "/%2f/evil.example" "/%2F%2Fevil.example" "/%5cevil.example"
                "/%09/evil.example" "/login" "/login?next=/x" "/login/x" "javascript:alert(1)" 42]]
    (is
     (= "/"
        (urls/safe-next path))
     (pr-str path))))

(deftest safe-next-allows-pages-that-start-like-login
  (is
   (= "/logins"
      (urls/safe-next "/logins"))))

(deftest session-entry-is-only-the-gateway-path
  (is
   (true?
    (urls/session-entry? "/sessions/bob/0b8f6c4e-0c39-4e57-9d1e-6b8bd5f0f3a1")))
  (is
   (true?
    (urls/session-entry? "/sessions/bob%40example.com/0b8f?conversation=ses_12345678")))
  (doseq [path ["/sessions/0b8f6c4e-0c39-4e57-9d1e-6b8bd5f0f3a1" "/sessions" "/sessions/" "/sessions/a/b/c"
                "/runs/a/b" "/sessions//b"]]
    (is
     (false?
      (urls/session-entry? path))
     path)))

(deftest safe-url-allows-http-and-local-paths
  (doseq [url ["https://ci.example/build/1" "http://example.com" "HTTPS://Example.com/x" "/sessions/bob/1"]]
    (is
     (= url
        (urls/safe-url url)))))

(deftest safe-url-rejects-other-schemes
  (doseq [url [nil "" "javascript:alert(1)" " javascript:alert(1)" "data:text/html,x" "vbscript:x"
               "//evil.example" "https:evil.example" "https:///evil" "/\\evil.example" "file:///etc/passwd"]]
    (is
     (nil? (urls/safe-url url))
     (pr-str url))))

(deftest with-query-appends-a-parameter
  (is
   (= "/sessions/bob/1?conversation=ses_1"
      (urls/with-query "/sessions/bob/1" :conversation "ses_1")))
  (is
   (= "/x?a=1&conversation=a%20b%26c"
      (urls/with-query "/x?a=1" :conversation "a b&c"))))
