(ns manager-svc.sessions.kube
  "Kubernetes REST API calls with the pod's service account."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [jsonista.core :as json])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security KeyStore]
           [java.security.cert CertificateFactory]
           [java.time Duration]
           [javax.net.ssl SSLContext TrustManagerFactory]))

(def service-account-dir "/var/run/secrets/kubernetes.io/serviceaccount")

(defn- ssl-context
  [ca-file]
  (let [certificate
        (with-open [in (io/input-stream ca-file)]
          (.generateCertificate (CertificateFactory/getInstance "X.509") in))

        store
        (doto (KeyStore/getInstance (KeyStore/getDefaultType))
          (.load nil nil)
          (.setCertificateEntry "kubernetes" certificate))

        trust
        (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
          (.init store))]
    (doto (SSLContext/getInstance "TLS")
      (.init nil (.getTrustManagers trust) nil))))

(defn in-cluster
  "Connection settings of the in-cluster API server, created lazily."
  []
  (delay
    (let [host
          (System/getenv "KUBERNETES_SERVICE_HOST")

          port
          (or (System/getenv "KUBERNETES_SERVICE_PORT") "443")]
      (when (string/blank? host)
        (throw (ex-info "Not running in a Kubernetes cluster (KUBERNETES_SERVICE_HOST is not set)" {})))
      {:base-url (str "https://" host ":" port)
       :token-file (io/file service-account-dir "token")
       :http (-> (HttpClient/newBuilder)
                 (.sslContext (ssl-context (io/file service-account-dir "ca.crt")))
                 (.connectTimeout (Duration/ofSeconds 5))
                 (.build))})))

(defn encode
  [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn request
  "{:status :body} with the body parsed from JSON (string keys)."
  [connection method path & [data content-type]]
  (let [{:keys [base-url token-file http]}
        @connection

        publisher
        (if (some? data)
          (HttpRequest$BodyPublishers/ofString (json/write-value-as-string data))
          (HttpRequest$BodyPublishers/noBody))

        request
        (-> (HttpRequest/newBuilder (URI/create (str base-url path)))
            (.timeout (Duration/ofSeconds 30))
            (.header "Authorization" (str "Bearer " (string/trim (slurp token-file))))
            (.header "Content-Type" (or content-type "application/json"))
            (.header "Accept" "application/json")
            (.method (string/upper-case (name method)) publisher)
            (.build))

        response
        (.send ^HttpClient http request (HttpResponse$BodyHandlers/ofString))

        body
        (.body response)]
    {:status (.statusCode response)
     :body (when-not (string/blank? body)
             (try (json/read-value body) (catch Exception _ body)))}))

(defn check!
  [{:keys [status body] :as response} & allowed]
  (when-not (or (<= 200 status 299) (some #{status} allowed))
    (throw (ex-info (str "Kubernetes API error " status ": " (get body "message" body)) {:status status})))
  response)
