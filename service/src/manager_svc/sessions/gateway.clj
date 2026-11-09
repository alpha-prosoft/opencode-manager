(ns manager-svc.sessions.gateway
  "Edge entry points of sessions:

     GET /sessions/<sub>/<id>   the manager hands the person over to the session host
     GET /api/gateway/auth      Traefik forward-auth for every request to a session host
     session hosts              requests reaching the manager directly wake the session

   The manager's login never reaches a session host. Entering a session
   carries the person over with a one-time handoff token; the session host
   then gets its own cookie, a token bound to that session (auth/session-token)
   that forward-auth accepts only for that session's host."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [manager-svc.auth :as auth]
            [manager-svc.edge.server :as server]
            [manager-svc.opencode :as opencode]
            [manager-svc.sessions.access :as access]
            [manager-svc.sessions.backend :as backend]
            [manager-svc.sessions.links :as links]
            [manager-svc.sessions.live :as live]
            [manager-svc.sessions.records :as records]
            [manager-svc.sessions.routing :as routing])
  (:import [java.net URLDecoder URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.time Instant]))

(def starting-page
  (str "<!doctype html>\n"
       "<html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"3\">\n"
       "<title>Starting session</title>\n"
       "<style>body{font-family:system-ui,sans-serif;display:grid;place-items:center;height:100vh;margin:0}</style>\n"
       "</head><body><p>Starting your session&hellip; this page refreshes automatically.</p></body></html>"))

(def conversation-pattern #"^ses_[A-Za-z0-9]{8,64}$")
(def entry-pattern #"^/sessions/([^/]+)/([^/]+)$")

(defn- redirect
  [status location]
  {:status status
   :headers {"Location" location}})

(defn- starting
  []
  {:status 503
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body starting-page})

(defn- status-only
  [status]
  {:status status})

(defn- cookies-of
  [{:keys [headers]}]
  (auth/parse-cookies (get headers "cookie")))

(defn- session-cookie
  [settings identity session-id]
  (auth/set-cookie settings
                   (auth/session-cookie settings)
                   (auth/session-token settings identity (str session-id))
                   auth/session-ttl-seconds))

(defn- decode
  "URL-decoded text, or nil when it is not valid percent-encoding."
  [text]
  (try
    (URLDecoder/decode ^String text StandardCharsets/UTF_8)
    (catch IllegalArgumentException _
      nil)))

(defn- query-pairs
  [query-string]
  (for [part (string/split (str query-string) #"&")
        :when (seq part)]
    (let [[k v] (string/split part #"=" 2)]
      [(decode k) part (some-> v decode)])))

(defn- split-uri
  [uri]
  (let [[path query]
        (string/split (str uri) #"\?" 2)]
    [(if (string/blank? path) "/" path) query]))

(defn handoff-of
  [uri]
  (let [[_ query]
        (split-uri uri)]
    (some (fn [[k _ v]] (when (= auth/handoff-param k) v)) (query-pairs query))))

(defn without-handoff
  [uri]
  (let [[path query]
        (split-uri uri)

        kept
        (keep (fn [[k raw _]] (when (not= auth/handoff-param k) raw)) (query-pairs query))]
    (if (seq kept)
      (str path "?" (string/join "&" kept))
      path)))

(defn- may-open?
  "can-open?, remembered for a few seconds per person and the session's
   sharing, so a lock or a visibility change counts at once."
  [system session username]
  (live/cached! system
                [:open username (select-keys session [:id :sub :team-id :visibility :locked-at])]
                live/decision-ttl-ms
                #(access/can-open? system session username)))

(defn- refresh-due?
  [{:keys [expires-at]}]
  (< (- (or expires-at 0) (.getEpochSecond (Instant/now)))
     (quot auth/session-ttl-seconds 2)))

(defn authorize
  "The session a request to a session host may reach, as
   [:ok session claims], or [:respond response] when it must be answered
   (redirect, 401, 404)."
  [system session-id uri request]
  (let [settings
        (:settings system)

        session
        (when session-id
          (records/one system session-id))

        handoff
        (handoff-of uri)]
    (cond
      (nil? session)
      [:respond (status-only 404)]

      handoff
      (let [identity
            (auth/read-handoff settings handoff (str (:id session)))]
        (if (and identity (access/can-open? system session (:sub identity)))
          [:respond (assoc-in (redirect 302 (str (links/session-url settings (:id session)) (without-handoff uri)))
                              [:headers "Set-Cookie"]
                              (session-cookie settings identity (:id session)))]
          [:respond (status-only 401)]))

      :else
      (let [claims
            (auth/read-session-token settings
                                     (get (cookies-of request) (auth/session-cookie settings))
                                     (str (:id session)))]
        (cond
          (nil? claims)
          [:respond (redirect 302 (links/entry-url settings session))]

          (not (may-open? system session (:sub claims)))
          [:respond (redirect 302 (links/manager-page settings session))]

          :else
          [:ok session claims])))))

(defn ensure-ready
  "True when the session's OpenCode answers; otherwise asks for a start
   unless one is pending already."
  [system {:keys [id pending]}]
  (cond
    (live/known-ready? system id)
    true

    (backend/active? (live/backend-of system) id)
    (live/ready? system id)

    (= :start pending)
    false

    :else
    (do
      (try
        (records/command! system :start-session id)
        (records/kick-lifecycle!)
        (catch Exception e
          (log/warn "Could not request session start" id (ex-message e))))
      false)))

(defn enter
  [{:keys [settings] :as system} {:keys [path query headers] :as request}]
  (let [[_ sub session-id]
        (re-matches entry-pattern path)

        identity
        (auth/resolve-identity settings (cookies-of request) (get headers "authorization"))

        session
        (when identity
          (records/one system session-id))

        conversation
        (:conversation query)]
    (cond
      (nil? identity)
      (redirect 303 (str "/login?next=" (URLEncoder/encode ^String path StandardCharsets/UTF_8)))

      (and conversation (not (re-matches conversation-pattern conversation)))
      (server/error 422 "Invalid conversation")

      (or (nil? session)
          (not= sub (:sub session))
          (not (access/can-view? system session (:sub identity))))
      (status-only 404)

      (not (access/can-open? system session (:sub identity)))
      (redirect 303 (str "/sessions/" (:id session)))

      :else
      (redirect 303 (str (links/session-url settings (:id session))
                         (links/open-path session conversation)
                         "?" auth/handoff-param "=" (auth/handoff-token settings identity (str (:id session))))))))

(defn forward-auth
  "Traefik's question about a request to a session host. Only the forwarded
   host counts, and the session cookie must be bound to that host's session."
  [{:keys [settings] :as system} {:keys [headers] :as request}]
  (let [session-id
        (links/session-id-from-host settings (get headers "x-forwarded-host"))

        [outcome result claims]
        (if session-id
          (authorize system session-id (or (get headers "x-forwarded-uri") "/") request)
          [:respond (status-only 404)])]
    (cond
      (= :respond outcome)
      result

      (not (ensure-ready system result))
      (starting)

      :else
      (do
        (records/touch! system (:id result))
        {:status 200
         :headers (cond-> {"Authorization" (opencode/basic-auth (links/secret-key settings (:id result)))}
                    (refresh-due? claims) (assoc "Set-Cookie" (session-cookie settings (auth/identity-of settings claims) (:id result))))}))))

(defn wake
  "Host handler: a request for a session host that reached the manager."
  [{:keys [settings] :as system} {:keys [host path query-string] :as request}]
  (when-let [session-id
             (when (not= routing/forward-auth-path path)
               (links/session-id-from-host settings host))]
    (let [uri
          (str path (when (seq query-string) (str "?" query-string)))

          [outcome result]
          (authorize system session-id uri request)]
      (if (= :respond outcome)
        result
        (do
          (ensure-ready system result)
          (starting))))))

(def routes
  [{:method :get :path routing/forward-auth-path :handler forward-auth}
   {:method :get :prefix "/sessions/" :match #(some? (re-matches entry-pattern (:path %))) :handler enter}])

(def host-handlers
  [wake])
