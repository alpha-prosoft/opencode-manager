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
   :jwt-jwks-file nil
   :jwt-discovery-url nil
   :jwt-jwks-refresh-seconds 3600
   :jwt-jwks-refresh-retry-seconds 10
   :jwt-jwks-default-algorithm nil
   :jwt-audience nil
   :jwt-issuer nil
   :content-security-policy nil
   :jwt-cookie "access_token"
   :oidc-issuer nil
   :oidc-discovery-url nil
   :oidc-jwks-file nil
   :oidc-jwks-refresh-seconds 3600
   :oidc-jwks-refresh-retry-seconds 10
   :oidc-jwks-default-algorithm nil
   :oidc-client-id nil
   :oidc-client-secret nil
   :oidc-client-secret-file nil
   :oidc-scopes "openid profile email"
   :oidc-redirect-url nil
   :oidc-claims-source "id_token"
   :email-domain "users.local"
   :credentials-key nil
   :tenants nil
   :tenants-file nil
   :tenant-file "tenant.json"
   :team-config-file "team.json"
   :claims-parser "manager-svc.claims/default-parser"
   :claims-parser-file nil
   :config-reload-seconds 10
   :operators []
   :dev-users-file nil
   :sync-interval-minutes 10
   :secrets-dir "/config/secrets"
   :repos-dir "/data/repos"
   :local-repo-roots ["/repos"]
   :http-git-hosts []
   :trusted-proxies []
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
    :session-port :reap-interval-seconds :idle-grace-seconds :config-reload-seconds
    :jwt-jwks-refresh-seconds :jwt-jwks-refresh-retry-seconds
    :oidc-jwks-refresh-seconds :oidc-jwks-refresh-retry-seconds})

(def ^:private list-keys
  #{:local-repo-roots :http-git-hosts :operators :trusted-proxies})

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
