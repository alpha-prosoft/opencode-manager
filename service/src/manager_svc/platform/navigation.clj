(ns manager-svc.platform.navigation
  "Projects as pages of their own, and the global search by name.

   A project is an entry of a team's team.json `projects`. :list-projects
   lists those of the caller's teams, :get-project one with the workflows
   that name it. :search finds projects of the caller's teams, teams of
   their tenant and sessions they may see, by a case-insensitive substring
   of their name. It reads the registry and the session directory rows
   (through the sessions facade), never edd's simple-search."
  (:require [clojure.string :as string]
            [edd.core :as edd]
            [manager-svc.errors :as errors]
            [manager-svc.platform.api :as api]
            [manager-svc.platform.projects :as projects]
            [manager-svc.platform.views :as views]
            [manager-svc.roles :as roles]
            [manager-svc.sessions.api :as sessions]
            [manager-svc.system :as system]))

(def default-limit
  5)

(def max-limit
  20)

(defn- named
  [value]
  (some-> value name))

(defn uses-project?
  [project-id definition]
  (= project-id (get-in definition [:spec :project])))

(defn delivers-changes?
  "A workflow whose pull requests (Gerrit: changes) live where the project's
   do: a review or an implementation, or one a pull request starts."
  [definition]
  (and (:valid definition)
       (or (contains? #{"review" "implement"} (named (get-in definition [:spec :activity])))
           (= "pull_request" (some-> (named (get-in definition [:spec :trigger :type])) (string/replace "-" "_"))))))

(defn- workflows-of
  [system tenant team-id project-id]
  (filterv #(uses-project? project-id %) (api/definitions system tenant team-id :workflows)))

(defn- listed-project
  [system tenant team project]
  (assoc (projects/view team project)
         :workflows (count (workflows-of system tenant (:id team) (:id project)))))

(defn list-projects
  "Projects of the caller's teams, or of one of them."
  [ctx {:keys [team-id]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        teams
        (if team-id
          [(views/visible-team! system caller team-id)]
          (keep #(api/team system tenant %) (sort (api/teams-of system caller))))]
    (vec (for [team teams
               project (:projects team)]
           (listed-project system tenant team project)))))

(defn get-project
  [ctx {:keys [team-id project-id]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        team
        (views/visible-team! system caller team-id)

        project
        (or (projects/project-of team project-id)
            (errors/not-found! (str "Project " project-id " is not in " (api/team-name team))))

        workflows
        (workflows-of system tenant team-id project-id)]
    {:tenant tenant
     :team {:id team-id
            :name (api/team-name team)
            :role (api/role system caller team-id)}
     :project (projects/view team project)
     :workflows (mapv views/definition-view workflows)
     :changes-workflow (:id (first (filter delivers-changes? workflows)))}))

(defn- matcher
  "What match-rank needs of a needle (lower case), made once per search."
  [needle]
  {:needle needle
   :word-start (re-pattern (str "(?:^|[\\s/._-])" (java.util.regex.Pattern/quote needle)))})

(defn- rank
  [{:keys [needle word-start]} names]
  (let [ranks
        (for [value names
              :when (string? value)
              :let [hay (string/lower-case value)]]
          (cond
            (= hay needle) 0
            (string/starts-with? hay needle) 1
            (re-find word-start hay) 2
            (string/includes? hay needle) 3
            :else nil))]
    (some->> (remove nil? ranks) seq (apply min))))

(defn match-rank
  "How well needle (lower case) matches the best of names: 0 exactly, 1 at
   the start, 2 at the start of a word, 3 anywhere; nil when it doesn't."
  [needle names]
  (rank (matcher needle) names))

(defn ranked
  "The best limit items whose names (names-of item) match text, best first:
   rank, then shorter names, then alphabetically."
  [text limit names-of items]
  (let [needle
        (string/lower-case (string/trim (str text)))

        matching
        (matcher needle)]
    (if (string/blank? needle)
      []
      (->> (for [item items
                 :let [names (names-of item)
                       found (rank matching names)
                       shown (str (first names))]
                 :when found]
             [[found (count shown) (string/lower-case shown)] item])
           (sort-by first)
           (take limit)
           (mapv second)))))

(defn- project-names
  [{:keys [name id scm]}]
  [name id (:repository scm)])

(defn- team-names
  [{:keys [name id]}]
  [name id])

(defn- session-names
  [{:keys [name]}]
  [name])

(defn- session-hit
  "A team session is the platform's: it has no owner to show."
  [team-names caller {:keys [id name team-id project visibility sub created-at] :as row}]
  (let [team-session
        (boolean (sessions/platform-owned? row))]
    {:id id
     :name name
     :team-id team-id
     :team-name (get team-names team-id team-id)
     :project project
     :visibility visibility
     :team-session team-session
     :owner (when-not team-session sub)
     :is-owner (= (:id caller) sub)
     :created-at created-at}))

(defn clamped-limit
  [limit]
  (max 1 (min max-limit (or limit default-limit))))

(defn search
  "{:projects :teams :sessions}, each best first and at most limit long."
  [ctx {:keys [text limit]}]
  (let [system
        (system/of ctx)

        caller
        (roles/caller ctx)

        tenant
        (:tenant caller)

        limit
        (clamped-limit limit)

        teams
        (when tenant (api/teams system tenant))

        mine
        (api/teams-of system caller)

        names
        (into {} (map (juxt :id api/team-name)) teams)]
    (if (or (nil? tenant) (string/blank? text))
      {:projects [] :teams [] :sessions []}
      {:projects (ranked text limit project-names
                         (for [team teams
                               :when (contains? mine (:id team))
                               project (:projects team)]
                           (projects/view team project)))
       :teams (ranked text limit team-names
                      (for [team teams]
                        {:id (:id team)
                         :name (api/team-name team)
                         :description (:description team)
                         :role (api/role system caller (:id team))
                         :member (contains? mine (:id team))}))
       :sessions (ranked text limit session-names
                         (map #(session-hit names caller %)
                              (views/guarded [] sessions/visible-rows system caller)))})))

(defn register
  [ctx]
  (-> ctx
      (edd/reg-query :list-projects list-projects
                     :auth roles/people
                     :consumes [:map [:team-id {:optional true} [:maybe string?]]])
      (edd/reg-query :get-project get-project
                     :auth roles/people
                     :consumes [:map [:team-id string?] [:project-id string?]])
      (edd/reg-query :search search
                     :auth roles/people
                     :consumes [:map
                                [:text [:string {:max 200}]]
                                [:limit {:optional true} [:maybe [:int {:min 1 :max max-limit}]]]])))
