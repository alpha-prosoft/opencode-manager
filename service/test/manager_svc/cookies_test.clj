(ns manager-svc.cookies-test
  (:require [clojure.test :refer [deftest is]]
            [manager-svc.cookies :as cookies]
            [manager-svc.test-support :as support]))

(def settings
  support/settings)

(deftest duplicate-cookies-are-dropped
  (is
   (= {"other" "1"}
      (cookies/parse "oc_auth=a; other=1; oc_auth=b")))
  (is
   (= {"oc_auth" "a"}
      (cookies/parse "oc_auth=a"))))

(deftest cookies-get-the-host-prefix-on-https
  (is
   (= "oc_auth"
      (cookies/login-cookie settings)))
  (is
   (= "__Host-oc_auth"
      (cookies/login-cookie (assoc settings :public-scheme "https"))))
  (is
   (= "__Host-oc_auth=t; Path=/; HttpOnly; SameSite=Lax; Max-Age=10; Secure"
      (cookies/set-cookie (assoc settings :public-scheme "https") "__Host-oc_auth" "t" 10))))
