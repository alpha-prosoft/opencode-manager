(ns manager-svc.claims
  "Who a person is, from the claims of their identity token. Membership
   comes only from claims: configuration files hold no member lists.

   A claims parser is a function of the token's claims (keyword keys as the
   token has them, e.g. :preferred_username, :realm_access) returning

     {:username \"alice\"            ; optional, defaults to the sub claim
      :email \"alice@acme.test\"     ; optional
      :name \"Alice\"                ; optional
      :tenant \"acme\"               ; required unless :operator
      :teams [{:id \"demo\" :role :admin} {:id \"ops\" :role :member}]
      :tenant-admin true            ; optional
      :operator true}               ; optional

   MANAGER_CLAIMS_PARSER names it (a fully qualified symbol, default
   manager-svc.claims/default-parser); MANAGER_CLAIMS_PARSER_FILE is a .clj
   file loaded at startup that may define it, so operators plug in their
   own without rebuilding. The file is loaded again when it changes
   (checked every MANAGER_CONFIG_RELOAD_SECONDS) or an operator asks
   (reload!); a file that fails to load, or no longer yields the parser,
   keeps the last good parser. Its output is validated: invalid output refuses
   the login with a message saying why. Usernames in MANAGER_OPERATORS are
   operators whatever the parser says.

   default-parser reads what Keycloak sends with a group membership mapper
   (full group paths) and realm roles:

     preferred_username  the username (sub when it isn't a valid one)
     email, name
     tenant              the tenant (a user attribute mapper); without it,
                         the tenant all groups agree on
     groups              \"/<tenant>/<team>\" member of team,
                         \"/<tenant>/<team>/admin\" admin of team;
                         groups of other tenants are ignored
     realm_access.roles  \"tenant-admin\" administers the tenant,
     or roles            \"platform-operator\" is an operator

   In login mode (local development) MANAGER_DEV_USERS_FILE maps usernames
   to the claims a token would carry (JSON or EDN); they go through the same
   parser."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [manager-svc.names :as names]
            [manager-svc.reload :as reload]))

(def tenant-admin-role "tenant-admin")

(def operator-role "platform-operator")

(def Name
  [:re {:error/message "should be lowercase letters, digits and dashes"} names/identifier-pattern])

(def Parsed
  [:map
   [:username {:optional true} [:maybe [:re {:error/message "should be a lowercase username"} names/username-pattern]]]
   [:email {:optional true} [:maybe string?]]
   [:name {:optional true} [:maybe string?]]
   [:tenant {:optional true} [:maybe Name]]
   [:teams {:optional true} [:maybe [:sequential [:map
                                                  [:id Name]
                                                  [:role [:enum :admin :member]]]]]]
   [:tenant-admin {:optional true} [:maybe boolean?]]
   [:operator {:optional true} [:maybe boolean?]]])

(defn- single
  [value]
  (cond
    (string? value) value
    (and (sequential? value) (= 1 (count value)) (string? (first value))) (first value)
    :else nil))

(defn- group-of
  [path]
  (when (string? path)
    (let [parts
          (vec (remove string/blank? (string/split path #"/")))]
      (case (count parts)
        2 {:tenant (parts 0) :team (parts 1) :role :member}
        3 (when (= "admin" (parts 2)) {:tenant (parts 0) :team (parts 1) :role :admin})
        nil))))

(defn- roles-of
  [claims]
  (set (concat (get-in claims [:realm_access :roles])
               (let [roles (:roles claims)] (if (sequential? roles) roles [])))))

(defn- strongest
  [a b]
  (if (or (= :admin a) (= :admin b)) :admin :member))

(defn default-parser
  [claims]
  (let [groups
        (keep group-of (let [groups (:groups claims)] (if (sequential? groups) groups [])))

        group-tenants
        (distinct (map :tenant groups))

        tenant
        (or (single (:tenant claims))
            (when (= 1 (count group-tenants)) (first group-tenants)))

        teams
        (reduce (fn [acc {:keys [team role]}] (update acc team strongest role))
                (sorted-map)
                (filter #(= tenant (:tenant %)) groups))

        roles
        (roles-of claims)

        preferred
        (single (:preferred_username claims))]
    {:username (if (and preferred (re-matches names/username-pattern preferred)) preferred (:sub claims))
     :email (single (:email claims))
     :name (or (single (:name claims)) preferred)
     :tenant tenant
     :teams (mapv (fn [[id role]] {:id id :role role}) teams)
     :tenant-admin (contains? roles tenant-admin-role)
     :operator (contains? roles operator-role)}))

(defn- load-parser*
  [parser-name parser-file]
  (when-not (string/blank? parser-file)
    (load-file parser-file))
  (let [parser-symbol
        (symbol (or (not-empty parser-name) "manager-svc.claims/default-parser"))

        resolved
        (when (namespace parser-symbol)
          (try
            (requiring-resolve parser-symbol)
            (catch Exception _
              nil)))]
    (when-not resolved
      (throw (ex-info (str "Claims parser " parser-symbol " not found (MANAGER_CLAIMS_PARSER, MANAGER_CLAIMS_PARSER_FILE)") {})))
    (when-not (ifn? @resolved)
      (throw (ex-info (str "Claims parser " parser-symbol " is not a function") {})))
    @resolved))

(defn- parser-cell
  [{:keys [claims-parser claims-parser-file config-reload-seconds]}]
  (let [file
        (when-not (string/blank? claims-parser-file) claims-parser-file)]
    (reload/cell [:claims-parser claims-parser file]
                 (fn []
                   (cond-> {:name (str "Claims parser" (when file (str " (" file ")")))
                            :load (fn [_] (load-parser* claims-parser file))}
                     file (assoc :stamp (reload/file-stamp file)
                                 :check-ms (* 1000 (or config-reload-seconds 10))))))))

(defn parser
  "The configured claims parser function, the last good one while its file
   is broken."
  [settings]
  (reload/value! (parser-cell settings)))

(defn reload!
  "Loads the claims parser file again now: {:status :reloaded|:failed :error}."
  [settings]
  (reload/reload-now! (parser-cell settings)))

(def ^:private decoder
  (m/decoder Parsed (mt/transformer mt/string-transformer)))

(defn- explain
  [value]
  (when-let [explanation
             (m/explain Parsed value)]
    (string/join "; " (for [error (:errors explanation)]
                        (str (string/join "." (map #(if (keyword? %) (name %) (str %)) (:in error)))
                             " " (me/error-message error))))))

(defn- normalized
  [settings claims parsed]
  (let [username
        (or (:username parsed) (:sub claims))

        operator
        (or (true? (:operator parsed))
            (contains? (set (:operators settings)) username))]
    {:sub username
     :email (or (:email parsed) (str username "@" (:email-domain settings)))
     :name (or (:name parsed) username)
     :tenant (:tenant parsed)
     :teams (into (sorted-map) (map (juxt :id :role)) (:teams parsed))
     :tenant-admin (true? (:tenant-admin parsed))
     :operator operator}))

(defn parse
  "{:identity {:sub :email :name :tenant :teams {team-id role} :tenant-admin
   :operator}} for the claims of a token, or {:refused message}."
  [settings claims]
  (try
    (let [claims
          (walk/keywordize-keys claims)

          output
          ((parser settings) claims)

          parsed
          (decoder output)

          problem
          (if (map? parsed) (explain parsed) "the claims parser did not return a map")]
      (if problem
        {:refused (str "Login refused: the claims parser's answer is invalid (" problem ")")}
        (let [identity
              (normalized settings claims parsed)]
          (cond
            (not (and (string? (:sub identity)) (re-matches names/username-pattern (:sub identity))))
            {:refused "Login refused: the token names no valid username"}

            (names/platform-user? (:sub identity))
            {:refused "Login refused: platform is the platform's own name"}

            (and (nil? (:tenant identity)) (not (:operator identity)))
            {:refused "Login refused: your account belongs to no tenant"}

            :else
            {:identity identity}))))
    (catch Exception e
      {:refused (str "Login refused: the claims parser failed (" (ex-message e) ")")})))

(defn- read-file
  [path]
  (let [text
        (slurp path)]
    (if (string/ends-with? (string/lower-case path) ".edn")
      (edn/read-string text)
      (json/read-value text))))

(defn dev-users
  "username → claims of MANAGER_DEV_USERS_FILE, {} without one."
  [{:keys [dev-users-file]}]
  (if (and (not (string/blank? dev-users-file)) (.isFile (io/file dev-users-file)))
    (into (sorted-map)
          (map (fn [[username claims]] [(name username) (walk/keywordize-keys claims)]))
          (read-file dev-users-file))
    {}))

(defn dev-claims
  "The claims of a dev user (username alone when unknown)."
  [settings username]
  (merge {:sub username :preferred_username username}
         (get (dev-users settings) username)))

(defn role-labels
  [{:keys [operator tenant-admin teams]}]
  (vec (concat (when operator ["Operator"])
               (when tenant-admin ["Tenant admin"])
               (for [[team-id role] teams]
                 (str (if (= :admin role) "Team admin · " "Member · ") team-id)))))

(defn personas
  "The dev users the test login offers: [{:username :tenant :roles}]."
  [settings]
  (vec (for [username (keys (dev-users settings))
             :let [{:keys [identity refused]} (parse settings (dev-claims settings username))]]
         {:username username
          :tenant (:tenant identity)
          :roles (if refused [refused] (role-labels identity))})))

(defn check-settings!
  "Loads the parser at startup, so a broken one stops the manager."
  [settings]
  (parser settings))
