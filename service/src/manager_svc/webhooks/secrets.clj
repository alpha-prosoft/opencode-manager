(ns manager-svc.webhooks.secrets
  "A webhook's secret is sealed at the edge like a credential value, bound
   to the webhook and its tenant, so edd's request log, events and
   aggregates only ever hold the sealed value."
  (:require [manager-svc.credentials.api :as credentials])
  (:import [java.security SecureRandom]
           [java.util Base64]))

(defn- purpose
  [webhook-id]
  (str "webhook:" webhook-id))

(defn- owner
  [tenant]
  (str "tenant:" tenant))

(defn seal
  [settings tenant webhook-id value]
  (credentials/seal-secret settings (purpose webhook-id) (owner tenant) value))

(defn open
  "The {:secret :username} sealed for the webhook; 422 when it was sealed
   for anything else."
  [settings tenant webhook-id sealed]
  (credentials/open-secret settings (purpose webhook-id) (owner tenant) sealed
                           "The secret was not sealed for this webhook"))

(defn hint
  "What managers see of a secret; the username is the webhook's current
   one, not the one that was sealed with it."
  [auth value]
  (credentials/secret-hint (assoc (select-keys value [:secret]) :username (:username auth))))

(defn generate
  "A new random token: 32 bytes, URL-safe base64."
  []
  (let [bytes
        (byte-array 32)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))
