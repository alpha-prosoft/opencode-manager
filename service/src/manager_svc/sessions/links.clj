(ns manager-svc.sessions.links
  "Hosts, URLs and keys of a session. A session lives on its own subdomain
   <id>.<domain>. Its OpenCode server password and the keys its sandbox
   presents to the platform's gateways are derived from the id
   (manager-svc.auth/derived-key), one per purpose."
  (:require [clojure.string :as string]
            [manager-svc.auth :as auth]
            [manager-svc.config :as config]
            [manager-svc.opencode :as opencode])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def workspace-dir "/session/workspace")
(def conversation-label :conversation)
(def password-purpose "session")

(defn parse-id
  [value]
  (cond
    (uuid? value) value
    (string? value) (try (UUID/fromString value) (catch IllegalArgumentException _ nil))
    :else nil))

(defn session-url
  [settings session-id]
  (config/public-url settings (config/session-host settings session-id)))

(defn manager-url
  [settings path]
  (str (config/public-url settings (:domain settings)) path))

(defn entry-path
  [{:keys [id sub]}]
  (str "/sessions/" (URLEncoder/encode (str sub) StandardCharsets/UTF_8) "/" id))

(defn entry-url
  [settings session]
  (manager-url settings (entry-path session)))

(defn manager-page
  [settings {:keys [id]}]
  (manager-url settings (str "/sessions/" id)))

(defn open-path
  ([session]
   (open-path session nil))
  ([session conversation]
   (opencode/web-path workspace-dir (or conversation
                                        (get-in session [:labels conversation-label])))))

(defn session-id-from-host
  [settings host]
  (let [hostname
        (string/lower-case (first (string/split (str host) #":")))

        suffix
        (str "." (string/lower-case (:domain settings)))]
    (when (string/ends-with? hostname suffix)
      (parse-id (subs hostname 0 (- (count hostname) (count suffix)))))))

(defn secret-key
  "The session's OpenCode server password."
  [settings session-id]
  (auth/derived-key settings password-purpose session-id))
