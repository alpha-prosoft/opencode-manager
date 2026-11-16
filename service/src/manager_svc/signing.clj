(ns manager-svc.signing
  "The key the platform signs with, and the keys derived from it that a
   session's sandbox authenticates with: one per purpose, each naming its
   session."
  (:require [clojure.string :as string]
            [manager-svc.files :as files]
            [manager-svc.hash :as hash])
  (:import [java.security SecureRandom]
           [java.util Base64 UUID]))

(defn- random-key
  []
  (let [bytes
        (byte-array 48)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(defonce ^:private stored-keys
  (atom {}))

(defn signing-key
  "MANAGER_SECRET_KEY, else a key generated once into the data directory."
  [{:keys [secret-key data-dir]}]
  (if-not (string/blank? secret-key)
    secret-key
    (let [location
          (str data-dir "/signing.key")]
      (or (get @stored-keys location)
          (let [stored
                (files/read-or-create! location random-key)]
            (swap! stored-keys assoc location stored)
            stored)))))

(defn derived-key
  "A sandbox's key for purpose: <session-id>.<hex HMAC>."
  [settings purpose session-id]
  (str session-id "." (hash/hmac-hex (signing-key settings) (str purpose ":" session-id))))

(defn- parse-uuid-str
  [value]
  (try
    (UUID/fromString (str value))
    (catch IllegalArgumentException _
      nil)))

(defn derived-key-owner
  "The session id (a UUID) key was derived for with one of purposes, or nil."
  [settings key purposes]
  (when-let [session-id
             (parse-uuid-str (first (string/split (str key) #"\." 2)))]
    (when (some #(hash/equal? (derived-key settings % session-id) key) purposes)
      session-id)))
