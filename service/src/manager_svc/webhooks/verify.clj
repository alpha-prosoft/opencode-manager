(ns manager-svc.webhooks.verify
  "Checks of an incoming webhook request against the webhook's auth, like
   n8n's credential types:

     :none    nobody is checked (an explicit choice the UI warns about)
     :header  header-name carries the secret
     :basic   Authorization: Basic with username and the secret as password
     :bearer  Authorization: Bearer <secret>
     :hmac    header-name carries the HMAC (sha256 or sha1, hex, an optional
              \"sha256=\" prefix) of the raw body under the secret; with a
              timestamp-header the signed text is \"<timestamp>.<body>\" and
              the timestamp (unix seconds) must lie within
              tolerance-seconds of now, so a captured request can only be
              replayed inside that window (nothing remembers the requests
              already seen). The signature covers the body alone, never the
              query string or other headers, which is why a signed webhook
              maps its inputs from the body only and takes no GET

   Every comparison is constant-time. Results are {:ok true} or {:ok false
   :status 401|403 :reason \"...\"}; the reason goes to the delivery log
   only, callers get a generic answer."
  (:require [clojure.string :as string]
            [manager-svc.credentials.api :as credentials])
  (:import [java.net InetAddress]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64 HexFormat]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(set! *warn-on-reflection* true)

(def default-tolerance-seconds 300)

(defn- utf8
  ^bytes [text]
  (.getBytes (str text) StandardCharsets/UTF_8))

(defn equal?
  "Constant-time comparison of two texts; false when either is nil."
  [a b]
  (and (some? a)
       (some? b)
       (MessageDigest/isEqual (utf8 a) (utf8 b))))

(def ^:private mac-algorithms
  {:sha256 "HmacSHA256"
   :sha1 "HmacSHA1"})

(defn signature
  "Hex HMAC of the bytes of message under secret."
  [algorithm secret ^bytes message]
  (let [mac-name
        (get mac-algorithms (keyword algorithm) "HmacSHA256")

        mac
        (doto (Mac/getInstance mac-name)
          (.init (SecretKeySpec. (utf8 secret) mac-name)))]
    (.formatHex (HexFormat/of) (.doFinal mac message))))

(defn signed-bytes
  "What the HMAC covers: the body, or \"<timestamp>.<body>\"."
  ^bytes [timestamp ^bytes body]
  (if (string/blank? timestamp)
    body
    (byte-array (concat (utf8 (str timestamp ".")) body))))

(defn- presented-signature
  "The hex digest of a signature header: without a \"sha256=\"-style
   prefix, lower case."
  [value]
  (when-not (string/blank? value)
    (string/lower-case (string/trim (last (string/split (string/trim value) #"=" 2))))))

(defn- ok
  []
  {:ok true})

(defn- refused
  ([reason]
   (refused 401 reason))
  ([status reason]
   {:ok false :status status :reason reason}))

(defn- header
  [headers header-name]
  (get headers (string/lower-case (str header-name))))

(defn- authorization
  "[scheme value] of the Authorization header."
  [headers]
  (let [[scheme value]
        (string/split (string/trim (str (header headers "authorization"))) #"\s+" 2)]
    [(string/lower-case (str scheme)) value]))

(defn- basic-credentials
  [encoded]
  (try
    (let [decoded
          (String. (.decode (Base64/getDecoder) (string/trim (str encoded))) StandardCharsets/UTF_8)

          [username password]
          (string/split decoded #":" 2)]
      {:username username :password password})
    (catch IllegalArgumentException _
      nil)))

(defn- check-header
  [{:keys [header-name]} secret headers]
  (if (equal? (header headers header-name) secret)
    (ok)
    (refused (str "Header " header-name " is missing or wrong"))))

(defn- check-bearer
  [secret headers]
  (let [[scheme token]
        (authorization headers)]
    (if (and (= "bearer" scheme) (equal? token secret))
      (ok)
      (refused "Bearer token is missing or wrong"))))

(defn- check-basic
  [{:keys [username]} secret headers]
  (let [[scheme encoded]
        (authorization headers)

        presented
        (when (= "basic" scheme) (basic-credentials encoded))

        user-ok
        (equal? (:username presented) username)

        password-ok
        (equal? (:password presented) secret)]
    (if (and user-ok password-ok)
      (ok)
      (refused "Basic credentials are missing or wrong"))))

(defn- parse-long-safe
  [text]
  (try
    (Long/parseLong (string/trim (str text)))
    (catch NumberFormatException _
      nil)))

(defn- timestamp-problem
  [{:keys [timestamp-header tolerance-seconds]} headers now-seconds]
  (when-not (string/blank? timestamp-header)
    (let [stamp
          (parse-long-safe (header headers timestamp-header))

          tolerance
          (or tolerance-seconds default-tolerance-seconds)]
      (cond
        (nil? stamp) (str "Header " timestamp-header " is missing or not unix seconds")
        (> (abs (- now-seconds stamp)) tolerance) (str "Timestamp is outside the " tolerance " s window")
        :else nil))))

(defn- check-hmac
  [{:keys [header-name algorithm timestamp-header] :as auth} secret headers body now-seconds]
  (if-let [problem
           (timestamp-problem auth headers now-seconds)]
    (refused problem)
    (let [expected
          (signature (or algorithm :sha256) secret
                     (signed-bytes (when-not (string/blank? timestamp-header) (header headers timestamp-header)) body))

          presented
          (presented-signature (header headers header-name))]
      (if (equal? presented expected)
        (ok)
        (refused (str "Signature in " header-name " is missing or wrong"))))))

(defn check
  "Whether a request ({:headers (lower-case names) :body bytes}) passes auth
   with secret (the opened {:secret :username}) at now-seconds."
  [auth secret {:keys [headers body]} now-seconds]
  (let [kind
        (keyword (:type auth))

        value
        (:secret secret)]
    (cond
      (= :none kind) (ok)
      (string/blank? value) (refused "The webhook has no secret")
      :else (case kind
              :header (check-header auth value headers)
              :bearer (check-bearer value headers)
              :basic (check-basic auth value headers)
              :hmac (check-hmac auth value headers (or body (byte-array 0)) now-seconds)
              (refused (str "Unknown auth type " (name kind)))))))

(defn- address
  "The InetAddress of an IP literal, never a name lookup; nil otherwise."
  ^InetAddress [text]
  (let [literal
        (string/trim (str text))]
    (when (or (re-matches #"\d{1,3}(\.\d{1,3}){3}" literal)
              (and (string/includes? literal ":") (re-matches #"[0-9A-Fa-f:.]+" literal)))
      (try
        (InetAddress/getByName literal)
        (catch Exception _
          nil)))))

(defn- address-bytes
  ^bytes [text]
  (some-> (address text) .getAddress))

(defn valid-cidr?
  [cidr]
  (let [[address bits]
        (string/split (string/trim (str cidr)) #"/" 2)

        raw
        (address-bytes address)

        prefix
        (if bits (parse-long-safe bits) (when raw (* 8 (alength raw))))]
    (boolean (and raw prefix (<= 0 prefix (* 8 (alength raw)))))))

(defn- prefix-matches?
  [^bytes network ^bytes candidate prefix]
  (and (= (alength network) (alength candidate))
       (every? (fn [bit]
                 (let [index
                       (quot bit 8)

                       mask
                       (bit-shift-right 0x80 (rem bit 8))]
                   (= (bit-and (aget network index) mask)
                      (bit-and (aget candidate index) mask))))
               (range prefix))))

(defn in-cidr?
  [cidr ip]
  (let [[address bits]
        (string/split (string/trim (str cidr)) #"/" 2)

        network
        (address-bytes address)

        candidate
        (address-bytes ip)]
    (boolean (and network
                  candidate
                  (prefix-matches? network candidate (or (some-> bits parse-long-safe) (* 8 (alength network))))))))

(defn ip-allowed?
  "True when allowed-ips (CIDRs) is empty or one of them holds ip."
  [allowed-ips ip]
  (or (empty? allowed-ips)
      (boolean (some #(in-cidr? % ip) allowed-ips))))

(defn source-ip
  "The caller's address: the TCP peer, or, when the peer is one of trusted
   (the CIDRs of the proxies in front, MANAGER_TRUSTED_PROXIES), the last
   address of forwarded-for (every X-Forwarded-For line, joined), the one
   that proxy appended. Nobody else's X-Forwarded-For is believed."
  [trusted peer forwarded-for]
  (let [forwarded
        (last (re-seq #"[^,\s]+" (str forwarded-for)))]
    (if (and forwarded (seq trusted) (ip-allowed? trusted peer))
      forwarded
      peer)))

(defn sign-headers
  "The headers a caller sends to pass auth with secret ({:secret
   :username}) for body (bytes) at now-seconds; how the manager's own test
   invocation signs its request."
  [auth secret ^bytes body now-seconds]
  (let [value
        (:secret secret)

        {:keys [header-name algorithm timestamp-header]}
        auth]
    (case (keyword (:type auth))
      :header {header-name value}
      :bearer {"Authorization" (str "Bearer " value)}
      :basic {"Authorization" (credentials/authorization :basic {:username (:username auth) :secret value})}
      :hmac (let [stamp
                  (when-not (string/blank? timestamp-header) (str now-seconds))]
              (cond-> {header-name (str (name (or algorithm :sha256)) "="
                                        (signature (or algorithm :sha256) value (signed-bytes stamp body)))}
                stamp (assoc timestamp-header stamp)))
      {})))
