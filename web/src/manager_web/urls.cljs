(ns manager-web.urls
  "Which URLs the UI follows. Every href built from backend data and every
   redirect target after login goes through here."
  (:require [clojure.string :as string]))

(def ^:private unsafe-characters
  #"[\x00-\x20\x7f\\]")

(def ^:private encoded-separators
  #"(?i)%(?:2f|5c|[01][0-9a-f]|7f)")

(defn local-path?
  "A path on this origin that no browser reads as another host: one leading
   slash, no backslashes, whitespace, control characters or encoded slashes."
  [value]
  (and (string? value)
       (string/starts-with? value "/")
       (not (string/starts-with? value "//"))
       (not (re-find unsafe-characters value))
       (not (re-find encoded-separators value))))

(defn safe-url
  "value if it is an http(s) URL or a local path, else nil."
  [value]
  (when (string? value)
    (cond
      (re-find unsafe-characters value) nil
      (re-find #"(?i)^https?://[^/]" value) value
      (local-path? value) value
      :else nil)))

(defn safe-next
  "Where to go after logging in: a local path other than the login page
   itself, else home."
  [value]
  (if (and (local-path? value)
           (not (re-find #"^/login(?:[/?#]|$)" value)))
    value
    "/"))

(defn session-entry?
  "Paths served by the session gateway (/sessions/<owner>/<id>) rather than
   the UI, whose own session page is /sessions/<id>."
  [path]
  (boolean (re-matches #"/sessions/[^/?#]+/[^/?#]+(?:\?[^#]*)?" path)))

(defn with-query
  [url key value]
  (str url
       (if (string/includes? url "?") "&" "?")
       (js/encodeURIComponent (name key)) "=" (js/encodeURIComponent (str value))))

(defn setup-url
  "The setup page, going on to next-path once it is done."
  [next-path]
  (with-query "/setup" :next next-path))
