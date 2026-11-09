(ns manager-svc.edge.static
  "The built UI: files under ui-dist-dir, index.html for every other GET."
  (:require [clojure.java.io :as io]
            [clojure.string :as string])
  (:import [java.io File]
           [java.nio.file Files]))

(def ^:private content-types
  {"html" "text/html; charset=utf-8"
   "js" "application/javascript"
   "css" "text/css"
   "svg" "image/svg+xml"
   "png" "image/png"
   "ico" "image/x-icon"
   "json" "application/json"
   "woff2" "font/woff2"})

(def ui-config-script-hash
  "sha256 of index.html's inline script `window.eddconfig = {};`."
  "'sha256-jaXgaNHOrsarT9BfnLbZs9WFObHarHXMCi4sJJ97T38='")

(def default-csp
  (str "default-src 'self'; script-src 'self' " ui-config-script-hash "; img-src 'self' data:; "
       "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; "
       "object-src 'none'"))

(defn security-headers
  "MANAGER_CONTENT_SECURITY_POLICY replaces the policy (a shadow-cljs watch
   build needs eval and its websocket); \"off\" sends none."
  [settings]
  (let [policy
        (or (:content-security-policy settings) default-csp)]
    (cond-> {"X-Frame-Options" "DENY"}
      (not= "off" policy) (assoc "Content-Security-Policy" policy))))

(defn- extension
  [^File file]
  (last (string/split (.getName file) #"\.")))

(defn- file-response
  [settings ^File file]
  {:status 200
   :headers (assoc (security-headers settings)
                   "Content-Type" (get content-types (extension file) "application/octet-stream")
                   "Cache-Control" (if (= "html" (extension file)) "no-cache" "public, max-age=3600"))
   :body (Files/readAllBytes (.toPath file))})

(defn- inside?
  [^File root ^File file]
  (string/starts-with? (.getCanonicalPath file) (str (.getCanonicalPath root) File/separator)))

(defn- served?
  [^File root ^File file]
  (and (.isFile file)
       (inside? root file)
       (not= "map" (extension file))))

(defn handler
  [{:keys [settings]} {:keys [path]}]
  (let [root
        (io/file (:ui-dist-dir settings))

        candidate
        (io/file root (string/replace-first path #"^/+" ""))

        index
        (io/file root "index.html")]
    (cond
      (served? root candidate) (file-response settings candidate)
      (.isFile index) (file-response settings index)
      :else {:status 404 :headers (security-headers settings) :body "UI not built"})))

(def routes
  [{:method :get :prefix "/" :handler handler}])
