(ns manager-svc.platform.projects
  "Workflows that name a team project (\"project\": \"demo-service\") take
   their SCM source, repository, pull request delivery and CI job from the
   project in team.json. Whatever the workflow sets itself wins. Options are
   raw (snake_case keys, as written in the workflow file); the trigger is
   decoded."
  (:require [clojure.string :as string]
            [manager-svc.platform.spec :as spec]
            [manager-svc.rows :as rows]))

(defn project-of
  [team project-id]
  (rows/by-id (:projects team) project-id))

(defn repository-url
  "Where people browse the project's repository: the hosted provider's page,
   else the self-hosted server's web URL."
  [{:keys [provider web-url url] :as scm}]
  (cond
    (nil? provider) nil
    (spec/self-hosted? provider) (or web-url url)
    :else (string/replace (spec/scm-clone-url scm) #"\.git$" "")))

(defn view
  "A project of team as every query shows it; never its credential or the
   URL it is cloned from."
  [team {:keys [id scm ci branch] :as project}]
  (cond-> {:id id
           :name (or (:name project) id)
           :team-id (:id team)
           :team-name (or (:name team) (:id team))
           :branch (or branch "main")
           :scm (select-keys scm [:provider :repository :web-url])
           :repository-url (repository-url scm)}
    ci (assoc :ci (select-keys ci [:mcp-server :jobs :job]))))

(defn- raw
  "Decoded map → the snake_case keys and string enums of a workflow file."
  [m]
  (into {}
        (map (fn [[k v]]
               [(keyword (spec/snake k)) (if (keyword? v) (spec/snake v) v)]))
        m))

(defn- has?
  [options k]
  (some? (or (get options k) (get options (keyword (spec/snake k))))))

(defn- present
  [m]
  (into {} (remove (comp nil? val)) m))

(defn scm-source
  [project]
  (present (select-keys (:scm project) [:provider :repository :url :web-url :credentials])))

(defn repository-source
  "The git source of the project's repository."
  [project]
  (let [scm
        (:scm project)]
    (present {:url (spec/scm-clone-url scm)
              :branch (or (:branch project) "main")
              :credentials (:credentials scm)})))

(defn- ci-defaults
  [project]
  (let [ci
        (:ci project)]
    (when (:job ci)
      (select-keys ci [:mcp-server :job]))))

(defn- with-ci
  [options project]
  (let [defaults
        (ci-defaults project)

        own
        (or (:ci options) (:ci (raw options)))]
    (cond
      (nil? defaults) options
      (map? own) (assoc options :ci (merge (raw defaults) (raw own)))
      :else (assoc options :ci (raw defaults)))))

(defn- default-option
  [options k value]
  (if (has? options k)
    options
    (assoc options (keyword (spec/snake k)) value)))

(defn with-options
  [options activity project]
  (let [options
        (or options {})

        source
        (raw (scm-source project))

        repository
        (raw (repository-source project))]
    (case (name (or activity ""))
      "review" (if (or (has? options :scm) (has? options :repository))
                 options
                 (assoc options :scm source))
      "implement" (-> options
                      (default-option :repository repository)
                      (default-option :pull-request (dissoc source :credentials))
                      (with-ci project))
      (default-option options :repository repository))))

(defn with-trigger
  [trigger project]
  (if (= :pull-request (:type trigger))
    (merge (scm-source project) (present trigger))
    trigger))

(defn apply-project
  "{:workflow :errors}: the workflow with its project's settings filled in."
  [team workflow]
  (let [project-id
        (:project workflow)

        project
        (when project-id
          (project-of team project-id))]
    (cond
      (nil? project-id)
      {:workflow workflow :errors []}

      (nil? project)
      {:workflow workflow
       :errors [(str "project " project-id " is not in the team's team.json projects")]}

      :else
      {:workflow (-> workflow
                     (update :trigger with-trigger project)
                     (update :options with-options (:activity workflow) project))
       :errors []})))
