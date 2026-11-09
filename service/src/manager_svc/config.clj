(ns manager-svc.config
  (:require [clojure.string :as string]))

(def defaults
  {:backend "docker"
   :router "traefik-file"
   :store "memory"
   :data-dir "/data"
   :ui-dist-dir "/app/ui"
   :public-port-internal 8000
   :public-bind-host "0.0.0.0"
   :sandbox-port 8001
   :sandbox-bind-host "0.0.0.0"
   :edd-port 8081
   :max-body-bytes 1048576
   :domain "localhost"
   :public-scheme "http"
   :public-port nil
   :traefik-dynamic-file "/traefik/dynamic/sessions.yml"
   :traefik-entrypoint "web"
   :manager-service-url "http://manager:8000"
   :sandbox-service-url "http://manager:8001"
   :gateway-name "traefik-gateway"
   :gateway-namespace nil
   :gateway-section-name nil
   :forward-auth-middleware "opencode-forward-auth"
   :strip-cookie-middleware "opencode-strip-cookie"
   :auth-mode "jwt"
   :auth-ttl-hours 12
   :secret-key nil
   :jwt-secret nil
   :jwt-jwks-url nil
   :jwt-audience nil
   :jwt-issuer nil
   :content-security-policy nil
   :jwt-cookie "access_token"
   :email-domain "users.local"
   :credentials-key nil
   :onboarding-url nil
   :onboarding-type nil
   :onboarding-branch "main"
   :onboarding-credentials nil
   :onboarding-username "x-token-auth"
   :onboarding-file "platform.json"
   :sync-interval-minutes 10
   :secrets-dir "/config/secrets"
   :repos-dir "/data/repos"
   :local-repo-roots ["/repos"]
   :session-image "ghcr.io/anomalyco/opencode:1.19.5"
   :session-port 4096
   :shared-mount-path "/shared"
   :reap-interval-seconds 300
   :idle-grace-seconds 900
   :docker-sandbox-network "opencode-sandbox"
   :docker-egress-network "opencode-egress"
   :docker-shared-volume "opencode-shared"
   :k8s-namespace "default"
   :k8s-shared-pvc "opencode-shared"})

(def ^:private integer-keys
  #{:public-port-internal :sandbox-port :edd-port :public-port :max-body-bytes :auth-ttl-hours :sync-interval-minutes
    :session-port :reap-interval-seconds :idle-grace-seconds})

(def ^:private list-keys
  #{:local-repo-roots})

(defn- env-name
  [k]
  (str "MANAGER_" (string/upper-case (string/replace (name k) "-" "_"))))

(defn- parse-value
  [k raw]
  (cond
    (contains? integer-keys k) (Long/parseLong raw)
    (contains? list-keys k) (vec (remove string/blank? (string/split raw #",")))
    :else raw))

(defn load-settings
  ([]
   (load-settings (System/getenv)))
  ([env]
   (reduce-kv
    (fn [settings k default]
      (let [raw
            (get env (env-name k))]
        (assoc settings k (if (string/blank? raw)
                            default
                            (parse-value k raw)))))
    {}
    defaults)))

(defn session-host
  [settings session-id]
  (str session-id "." (:domain settings)))

(defn public-url
  [{:keys [public-scheme public-port]} host]
  (str public-scheme "://" host (when public-port (str ":" public-port))))
