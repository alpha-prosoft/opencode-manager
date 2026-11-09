(ns manager-svc.sessions.unix-http
  "A minimal HTTP/1.1 client over a Unix domain socket, enough for the Docker
   Engine API: one request per connection, response read until close. A
   request that takes longer than its deadline is cut off: the channel is
   closed, which ends a blocked read."
  (:require [clojure.string :as string]
            [jsonista.core :as json])
  (:import [java.io ByteArrayOutputStream]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels ClosedChannelException SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.util Arrays]
           [java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit]))

(def default-timeout-ms 30000)

(def ^:private deadlines
  (delay (Executors/newSingleThreadScheduledExecutor
          (reify ThreadFactory
            (newThread [_ runnable]
              (doto (Thread. ^Runnable runnable "docker-deadlines")
                (.setDaemon true)))))))

(defn- read-all
  ^bytes [^SocketChannel channel]
  (let [out
        (ByteArrayOutputStream.)

        buffer
        (ByteBuffer/allocate 65536)]
    (loop []
      (.clear buffer)
      (let [n
            (.read channel buffer)]
        (when (pos? n)
          (.write out (.array buffer) 0 n))
        (when-not (neg? n)
          (recur))))
    (.toByteArray out)))

(defn- index-of
  [^bytes haystack ^bytes needle]
  (let [limit
        (- (alength haystack) (alength needle))]
    (loop [i 0]
      (cond
        (> i limit) -1
        (Arrays/equals haystack i (+ i (alength needle)) needle 0 (alength needle)) i
        :else (recur (inc i))))))

(defn- dechunk
  ^bytes [^bytes body]
  (let [out
        (ByteArrayOutputStream.)

        crlf
        (.getBytes "\r\n" StandardCharsets/US_ASCII)]
    (loop [offset 0]
      (let [rest-bytes
            (Arrays/copyOfRange body offset (alength body))

            line-end
            (index-of rest-bytes crlf)]
        (when (pos? line-end)
          (let [size
                (Long/parseLong (string/trim (first (string/split (String. rest-bytes 0 line-end StandardCharsets/US_ASCII) #";")))
                                16)

                start
                (+ offset line-end 2)]
            (when (pos? size)
              (.write out body (int start) (int (min size (- (alength body) start))))
              (recur (+ start size 2)))))))
    (.toByteArray out)))

(defn- parse-response
  [^bytes raw]
  (let [separator
        (index-of raw (.getBytes "\r\n\r\n" StandardCharsets/US_ASCII))

        head
        (String. raw 0 (int (max separator 0)) StandardCharsets/ISO_8859_1)

        [status-line & header-lines]
        (string/split-lines head)

        headers
        (into {}
              (keep (fn [line]
                      (let [[k v] (string/split line #":" 2)]
                        (when v [(string/lower-case (string/trim k)) (string/trim v)]))))
              header-lines)

        body
        (Arrays/copyOfRange raw (int (+ separator 4)) (alength raw))]
    {:status (Long/parseLong (second (string/split status-line #" ")))
     :headers headers
     :body (String. (if (= "chunked" (get headers "transfer-encoding")) (dechunk body) body)
                    StandardCharsets/UTF_8)}))

(defn- with-deadline
  [^SocketChannel channel timeout-ms path f]
  (let [timer
        (.schedule ^ScheduledExecutorService @deadlines
                   ^Runnable (fn [] (.close channel))
                   (long timeout-ms)
                   TimeUnit/MILLISECONDS)]
    (try
      (f)
      (catch ClosedChannelException _
        (throw (ex-info (str "Docker API " path " timed out after " timeout-ms " ms")
                        {:timeout-ms timeout-ms})))
      (finally
        (.cancel timer false)))))

(defn request
  "{:status :headers :body} where body is text; data is sent as JSON. The
   request fails after timeout-ms (default-timeout-ms)."
  [socket-path method path & [data {:keys [timeout-ms] :or {timeout-ms default-timeout-ms}}]]
  (let [payload
        (when (some? data)
          (.getBytes ^String (json/write-value-as-string data) StandardCharsets/UTF_8))

        head
        (str (string/upper-case (name method)) " " path " HTTP/1.1\r\n"
             "Host: docker\r\n"
             "Connection: close\r\n"
             (when payload
               (str "Content-Type: application/json\r\nContent-Length: " (alength ^bytes payload) "\r\n"))
             (when (and (nil? payload) (#{:post :put} method))
               "Content-Length: 0\r\n")
             "\r\n")]
    (with-open [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
      (with-deadline channel timeout-ms path
        (fn []
          (.connect channel (UnixDomainSocketAddress/of ^String socket-path))
          (.write channel (ByteBuffer/wrap (.getBytes head StandardCharsets/ISO_8859_1)))
          (when payload
            (.write channel (ByteBuffer/wrap payload)))
          (parse-response (read-all channel)))))))

(defn read-json
  [{:keys [body]}]
  (when-not (string/blank? body)
    (json/read-value body)))
