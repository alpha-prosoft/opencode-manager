(ns manager-svc.cookies
  "The cookies of the manager's hosts: their names, reading a Cookie header
   and writing a Set-Cookie one."
  (:require [clojure.string :as string]))

(defn- secure?
  [settings]
  (= "https" (:public-scheme settings)))

(defn host-cookie
  "Name of a cookie of the manager host; __Host- on https, so session
   subdomains can't set or shadow it."
  [settings base-name]
  (if (secure? settings) (str "__Host-" base-name) base-name))

(defn login-cookie
  "Holds a person's platform token."
  [settings]
  (host-cookie settings "oc_auth"))

(defn session-cookie
  "Holds a session host's token."
  [settings]
  (host-cookie settings "oc_session"))

(defn set-cookie
  "A Set-Cookie value for a host-only cookie."
  [settings cookie-name value max-age]
  (str cookie-name "=" value
       "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" max-age
       (when (secure? settings) "; Secure")))

(defn parse
  "Cookies of a Cookie header. A name sent more than once is left out, so a
   cookie set by another host can't shadow the real one."
  [header]
  (if (string/blank? header)
    {}
    (let [pairs
          (keep (fn [part]
                  (let [[k v] (string/split (string/trim part) #"=" 2)]
                    (when (and (seq k) v) [k v])))
                (string/split header #";"))

          repeated
          (set (keep (fn [[k n]] (when (> n 1) k)) (frequencies (map first pairs))))]
      (into {} (remove (comp repeated first)) pairs))))
