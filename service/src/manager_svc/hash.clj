(ns manager-svc.hash
  "Hex digests and HMACs over UTF-8 text."
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn- hex
  [^bytes data]
  (.formatHex (HexFormat/of) data))

(defn- utf8
  ^bytes [text]
  (.getBytes (str text) StandardCharsets/UTF_8))

(defn sha256-hex
  [text]
  (hex (.digest (MessageDigest/getInstance "SHA-256") (utf8 text))))

(defn sha1-hex
  [text]
  (hex (.digest (MessageDigest/getInstance "SHA-1") (utf8 text))))

(defn hmac-hex
  [key message]
  (let [mac
        (doto (Mac/getInstance "HmacSHA256")
          (.init (SecretKeySpec. (utf8 key) "HmacSHA256")))]
    (hex (.doFinal mac (utf8 message)))))

(defn equal?
  "Constant-time comparison of two texts."
  [a b]
  (MessageDigest/isEqual (utf8 a) (utf8 b)))
