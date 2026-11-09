(ns manager-svc.reload
  "Values loaded from something that changes while the manager runs: an
   identity provider's discovery document and keys, the claims parser file,
   the tenants file. A cell holds the last good value and loads it again

     - when it is older than :refresh-ms,
     - when its :stamp (a file's modification time and size, checked at
       most every :check-ms) differs from the one it was loaded with,
     - when a caller asks (refresh!, e.g. a token naming an unknown key),
       at most once every :retry-ms after the last attempt, so callers
       can't flood the source,
     - when an operator asks (reload-now!), whatever the time.

   :load gets the previous value and returns the new one or throws; a
   failed load keeps the last good value and is logged. Concurrent callers
   needing a load share one attempt (single flight); an operator's reload
   always makes its own."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn now-ms
  []
  (System/currentTimeMillis))

(defonce ^:private cells
  (atom {}))

(defn cell
  "The cell known by key, made from the options (make) the first time:
   {:name :load :refresh-ms :retry-ms :stamp :check-ms}."
  [key make]
  (or (get @cells key)
      (let [created
            (assoc (make) :state (atom {}) :inflight (atom nil))]
        (get (swap! cells #(if (contains? % key) % (assoc % key created))) key))))

(defn file-stamp
  "A stamp function of the file at path: its modification time and size."
  [path]
  (fn []
    (let [file
          (io/file path)]
      [(.lastModified file) (.length file)])))

(defn- elapsed?
  [since now ms]
  (or (nil? since) (>= (- now since) (or ms 0))))

(defn- loaded?
  [state]
  (contains? state :value))

(defn- stale?
  [{:keys [refresh-ms]} state now]
  (or (not (loaded? state))
      (and (some? refresh-ms) (elapsed? (:ok-at state) now refresh-ms))))

(defn- stamp-differs?
  [{:keys [stamp state]}]
  (and (some? stamp) (not= (stamp) (:stamp @state))))

(defn- stamp-changed?
  "Reads the stamp at most every :check-ms."
  [{:keys [stamp check-ms state] :as cell} now]
  (when (and stamp (elapsed? (:stamp-checked-at @state) now check-ms))
    (swap! state assoc :stamp-checked-at now)
    (stamp-differs? cell)))

(defn- may-retry?
  [{:keys [retry-ms]} state now]
  (elapsed? (:checked-at state) now retry-ms))

(defn- attempt!
  [{:keys [name load stamp state]}]
  (let [now
        (now-ms)

        current-stamp
        (when stamp (stamp))

        previous
        @state]
    (try
      (let [value
            (load (:value previous))]
        (swap! state assoc
               :value value
               :ok-at now
               :checked-at now
               :stamp current-stamp
               :stamp-checked-at now
               :error nil)
        (when (loaded? previous)
          (log/info name "reloaded"))
        {:status :reloaded})
      (catch Exception e
        (swap! state assoc
               :checked-at now
               :stamp current-stamp
               :stamp-checked-at now
               :error (ex-message e))
        (if (loaded? previous)
          (log/warn name "could not be reloaded, keeping the last good one:" (ex-message e))
          (log/warn name "could not be loaded:" (ex-message e)))
        {:status :failed :error (ex-message e)}))))

(defn- fly!
  "This caller's attempt, made when (due?) still holds; its outcome goes to
   the callers waiting on mine."
  [{:keys [inflight] :as cell} mine due?]
  (let [outcome
        (volatile! {:status :current})]
    (try
      (when (due?)
        (vreset! outcome {:status :failed :error "interrupted"})
        (vreset! outcome (attempt! cell)))
      (finally
        (deliver mine @outcome)
        (compare-and-set! inflight mine nil)))
    @outcome))

(defn- load!
  "One attempt when (due?) still holds once it is this caller's turn.
   Callers arriving while one runs wait for it and share its outcome, or
   with :own-attempt? make theirs after it."
  [{:keys [inflight] :as cell} due? & {:keys [own-attempt?]}]
  (let [mine
        (promise)]
    (loop []
      (if (compare-and-set! inflight nil mine)
        (fly! cell mine due?)
        (let [other
              @inflight]
          (if (and other (not own-attempt?))
            @other
            (do
              (some-> other deref)
              (recur))))))))

(defn- still-due?
  [{:keys [state] :as cell}]
  (let [now
        (now-ms)]
    (and (or (stale? cell @state now) (stamp-differs? cell))
         (may-retry? cell @state now))))

(defn value
  "The cell's value, loaded first when it is due; the last good value when
   loading fails, nil when nothing ever loaded."
  [{:keys [state] :as cell}]
  (let [now
        (now-ms)

        due?
        (or (stale? cell @state now)
            (stamp-changed? cell now))]
    (when (and due? (may-retry? cell @state now))
      (load! cell #(still-due? cell)))
    (:value @state)))

(defn value!
  "The cell's value, or an exception with why it never loaded."
  [{:keys [state name] :as cell}]
  (let [current
        (value cell)]
    (if (loaded? @state)
      current
      (throw (ex-info (str name ": " (:error @state)) {})))))

(defn refresh!
  "Loads the cell again unless the last attempt was less than :retry-ms
   ago; true when it tried (or shared another caller's attempt)."
  [{:keys [state] :as cell}]
  (when (may-retry? cell @state (now-ms))
    (load! cell #(may-retry? cell @state (now-ms)))
    true))

(defn reload-now!
  "Loads the cell again now, after a load already under way:
   {:status :reloaded|:failed :error}."
  [cell]
  (load! cell (constantly true) :own-attempt? true))

(defn error
  "Why the last attempt failed, nil when it succeeded."
  [{:keys [state]}]
  (:error @state))
