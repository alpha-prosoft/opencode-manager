(ns manager-svc.workers
  "Background jobs replacing the Temporal worker. A job is

     {:id :sync :interval-seconds 600 :initial-delay-seconds 5 :run (fn [system])
      :reactive true}

   Reactive jobs also run right after any command the UI sends (`kick!`),
   so commands stay pure state changes and jobs carry out the side effects
   (start a sandbox, run an agent) without waiting for the next interval.

   Jobs run on a scheduled pool; a failing run is logged and retried at the
   next interval. A job never runs twice at once: a kick or tick that comes
   while it runs makes it run once more afterwards. State lives in edd
   aggregates, so a job only reconciles."
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defn- run-once
  [system {:keys [id run]}]
  (try
    (run system)
    (catch Throwable t
      (log/error t "Job failed" id))))

(defn- finished?
  "Leaves :running unless another run was asked for meanwhile."
  [state]
  (= :idle (swap! state {:running :idle :again :running})))

(defn serialized
  "The job as a Runnable that never overlaps itself; state is an atom
   holding :idle, :running or :again."
  [system job state]
  (fn []
    (when (= :running (swap! state {:idle :running :running :again :again :again}))
      (loop []
        (run-once system job)
        (when-not (finished? state)
          (recur))))))

(defonce ^:private running
  (atom nil))

(defn kick!
  "Runs every reactive job now (or only the job job-id), once per kick, on
   the job pool."
  ([]
   (kick! nil))
  ([job-id]
   (when-let [{:keys [pool runnables jobs]}
              @running]
     (doseq [job jobs
             :when (if job-id (= job-id (:id job)) (:reactive job))]
       (.submit ^ScheduledExecutorService pool ^Runnable (get runnables (:id job)))))))

(defn start
  [system jobs]
  (let [pool
        (Executors/newScheduledThreadPool (max 2 (count jobs)))

        runnables
        (into {} (map (fn [job] [(:id job) (serialized system job (atom :idle))])) jobs)]
    (reset! running {:pool pool :jobs jobs :runnables runnables})
    (doseq [{:keys [id interval-seconds initial-delay-seconds]} jobs]
      (.scheduleWithFixedDelay ^ScheduledExecutorService pool
                               ^Runnable (get runnables id)
                               (long (or initial-delay-seconds 5))
                               (long interval-seconds)
                               TimeUnit/SECONDS))
    (log/info "Workers started" (mapv :id jobs))
    pool))

(defn stop
  [^ScheduledExecutorService pool]
  (reset! running nil)
  (.shutdownNow pool))
