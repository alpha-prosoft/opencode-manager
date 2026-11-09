(ns manager-svc.credentials.vault
  "AES-GCM sealing of credential values. A sealed value is bound to its
   credential id and owner, so it cannot be replayed under another one."
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [lambda.util :as util]
            [manager-svc.files :as files])
  (:import [java.nio.charset StandardCharsets]
           [java.security SecureRandom]
           [java.util Arrays Base64]
           [javax.crypto Cipher]
           [javax.crypto.spec GCMParameterSpec SecretKeySpec]))

(def platform-owner "")

(def ^:private nonce-length 12)
(def ^:private tag-bits 128)

(defn- random-bytes
  [n]
  (let [bytes
        (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    bytes))

(defn- encode
  [^bytes bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn- decode
  ^bytes [^String text]
  (.decode (Base64/getDecoder) (string/trim text)))

(defn- generated-key
  [location]
  (log/warn "MANAGER_CREDENTIALS_KEY is not set; generated" location ". Set the key explicitly in production.")
  (encode (random-bytes 32)))

(defonce ^:private key-files
  (atom {}))

(defn- key-file-value
  [data-dir]
  (let [location
        (str data-dir "/credentials.key")]
    (or (get @key-files location)
        (let [stored
              (files/read-or-create! location #(generated-key location))]
          (swap! key-files assoc location stored)
          stored))))

(defn master-key
  "MANAGER_CREDENTIALS_KEY, else a key generated once into the data directory."
  ^bytes [{:keys [credentials-key data-dir]}]
  (let [key
        (decode (if (string/blank? credentials-key)
                  (key-file-value data-dir)
                  credentials-key))]
    (when-not (= 32 (alength key))
      (throw (ex-info "MANAGER_CREDENTIALS_KEY must be 32 bytes, base64 encoded" {})))
    key))

(defn- aad
  ^bytes [credential-id owner]
  (.getBytes (str credential-id "\u0000" owner) StandardCharsets/UTF_8))

(defn- cipher
  ^Cipher [settings mode ^bytes nonce credential-id owner]
  (let [cipher
        (Cipher/getInstance "AES/GCM/NoPadding")]
    (.init cipher
           (int mode)
           (SecretKeySpec. (master-key settings) "AES")
           (GCMParameterSpec. (int tag-bits) nonce))
    (.updateAAD cipher (aad credential-id owner))
    cipher))

(defn seal
  "Base64 of nonce and ciphertext of value ({:secret :username})."
  [settings credential-id owner value]
  (let [nonce
        (random-bytes nonce-length)

        plaintext
        (.getBytes ^String (util/to-json value) StandardCharsets/UTF_8)

        ciphertext
        (.doFinal (cipher settings Cipher/ENCRYPT_MODE nonce credential-id owner) plaintext)]
    (encode (byte-array (concat nonce ciphertext)))))

(defn unseal
  [settings credential-id owner sealed]
  (let [bytes
        (decode sealed)

        nonce
        (Arrays/copyOfRange bytes 0 (int nonce-length))

        ciphertext
        (Arrays/copyOfRange bytes (int nonce-length) (alength bytes))

        plaintext
        (.doFinal (cipher settings Cipher/DECRYPT_MODE nonce credential-id owner) ciphertext)]
    (select-keys (util/to-edn (String. plaintext StandardCharsets/UTF_8)) [:secret :username])))

(defn hint
  "What people see of a stored value: the username and the last four
   characters of a long enough secret."
  [{:keys [secret username]}]
  (let [trimmed
        (string/trim (str secret))

        tail
        (when (>= (count trimmed) 12)
          (subs trimmed (- (count trimmed) 4)))]
    (cond
      (not (string/blank? username)) (str username " · …" tail)
      tail (str "…" tail)
      :else "set")))
