(ns manager-svc.runs.model
  "Run statuses and the shapes the UI reads (Run, RunOutcome, RunDetail in
   manager/ui/src/api.ts, kebab-case).")

(def active-statuses
  [:queued :preparing :running :awaiting-result :finalizing :following-ci])

(def finished-statuses
  [:completed :failed :cancelled])

(defn finished?
  [run]
  (contains? (set finished-statuses) (:status run)))

(defn active?
  [run]
  (contains? (set active-statuses) (:status run)))

(defn platform-created?
  "True for a run the platform started on its own (a trigger, a schedule, a
   follow-up of such a run): it works with team credentials."
  [run]
  (true? (:platform-started run)))

(defn person
  "The person a run acts for, nil when the platform started it."
  [run]
  (when-not (platform-created? run)
    (:created-by run)))

(def run-keys
  [:id :team-id :workflow-id :activity :status :trigger :inputs :session-id
   :created-by :platform-started :created-at :cancelled-by])

(defn outcome
  "The few facts a run list shows: the pull request, its build, or the review verdict."
  [result]
  (let [pull-request
        (:pull-request result)

        ci
        (:ci result)

        verdict
        (get-in result [:review :verdict])

        facts
        (cond-> {}
          pull-request (assoc :pull-request (select-keys pull-request [:id :url :draft :updated]))
          ci (assoc :build {:state (:state ci)
                            :number (get-in ci [:build :number])
                            :follow-up-run (:follow-up-run ci)})
          verdict (assoc :verdict verdict))]
    (not-empty facts)))

(defn summary
  "What a run list shows. The run's error is :error-message: edd answers a
   query result holding :error as an error."
  [run workflow-name]
  (assoc (select-keys run run-keys)
         :error-message (:error run)
         :has-result (boolean (seq (:result run)))
         :outcome (outcome (:result run))
         :workflow-name (or workflow-name (:workflow-id run))))

(defn needs-work?
  "Active runs, and finished ones whose session or notification is still due."
  [run]
  (or (active? run)
      (and (finished? run)
           (or (and (:session-id run) (not (:session-released run)))
               (and (contains? #{:completed :failed} (:status run))
                    (not (:notified run)))))))

(defn row
  "A run's directory row: what a run list shows, without the workflow name."
  [run]
  (assoc (dissoc (summary run nil) :workflow-name)
         :needs-work (needs-work? run)
         :version (:version run)))

(defn listed
  "What a run list shows of a directory row."
  [row workflow-name]
  (assoc (dissoc row :needs-work :version)
         :workflow-name (or workflow-name (:workflow-id row))))

(defn detail
  [run workflow-name session usage]
  (assoc (summary run workflow-name)
         :result (:result run)
         :session session
         :usage usage))
