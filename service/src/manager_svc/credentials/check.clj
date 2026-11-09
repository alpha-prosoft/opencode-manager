(ns manager-svc.credentials.check
  "Checks a credential value against its service before it is saved."
  (:require [clojure.string :as string]
            [manager-svc.credentials.mcp :as mcp]
            [manager-svc.http :as http])
  (:import [java.net URI]
           [java.net.http HttpTimeoutException]))

(defn- host-of
  [url]
  (.getHost (URI/create url)))

(defn- through-url
  [credential value url]
  (let [host
        (host-of url)

        scheme
        (if (= :token (:kind credential)) :bearer :basic)]
    (try
      (let [{:keys [status headers]}
            (http/request! {:method :get
                            :url url
                            :headers {"Authorization" (mcp/authorization scheme value)}
                            :timeout-seconds 10})

            location
            (string/lower-case (str (get headers "location")))]
        (cond
          (and (<= 300 status 399) (string/includes? location "login"))
          {:ok false :message (str host " redirected to its login page: the credential was not accepted.")}

          (<= 200 status 299)
          {:ok true :message "Checked: the service accepted it."}

          :else
          {:ok false :message (str "The service rejected it (HTTP " status ").")}))
      (catch HttpTimeoutException _
        {:ok false :message (str "Could not reach " host " from the platform (timed out). Is it only reachable through a VPN?")})
      (catch java.io.IOException e
        {:ok false :message (str "Could not reach " host " from the platform (" (.getSimpleName (class e)) ").")}))))

(defn- through-mcp
  [credential value tool mcp-servers]
  (if-let [server
           (some #(when (= (:id credential) (:credential %)) %) mcp-servers)]
    (try
      (let [answer
            (mcp/call-tool server value tool {} 20)

            detail
            (if (coll? answer) (http/write-json answer) (str answer))]
        {:ok true
         :message (str "Checked through " (:name server) ": " (subs detail 0 (min 200 (count detail))))})
      (catch Exception e
        (if (mcp/tool-failed? e)
          {:ok false :message (ex-message e)}
          (throw e))))
    {:ok false :message (str "No MCP server uses " (:name credential) ", so it can't be checked through one.")}))

(defn test-value
  "{:ok :message} for value of credential (a declaration with defaults)."
  [credential value mcp-servers]
  (let [{:keys [url mcp-tool]}
        (:test credential)]
    (cond
      (not (string/blank? mcp-tool)) (through-mcp credential value mcp-tool mcp-servers)
      (not (string/blank? url)) (through-url credential value url)
      :else {:ok true :message "Saved. This credential has no automatic check."})))

(defn verifiable?
  [credential]
  (some? (:test credential)))
