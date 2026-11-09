(ns manager-svc.credentials.required
  "Credentials people must provide themselves. tenant.json marks a credential
   required (\"required\": true) for everyone in the teams it applies to; a
   team's team.json requires credentials of its members
   (\"required_credentials\"). Only the person's own value satisfies a
   requirement: a team or tenant value doesn't, since the point is that the
   tools act as that person."
  (:require [clojure.string :as string]
            [manager-svc.credentials.spec :as spec]))

(defn by-team?
  "True when team (an entry with :required-credentials) requires credential-id."
  [team credential-id]
  (boolean (some #{(name credential-id)} (:required-credentials team))))

(defn required-in?
  "True when people working in team must provide credential themselves."
  [credential team]
  (and (spec/for-teams? (:teams credential) [(:id team)])
       (or (true? (:required credential))
           (by-team? team (:id credential)))))

(defn required-by
  "{:tenant :teams}: whether tenant.json requires credential in one of teams,
   and the ids of the teams whose team.json requires it."
  [credential teams]
  (let [applies
        (filter #(spec/for-teams? (:teams credential) [(:id %)]) teams)]
    {:tenant (boolean (and (true? (:required credential)) (seq applies)))
     :teams (vec (sort (for [team applies
                             :when (by-team? team (:id credential))]
                         (:id team))))}))

(defn required?
  [{:keys [tenant teams]}]
  (boolean (or tenant (seq teams))))

(defn missing-in
  "Credentials of tenant-spec required in team for which own-ids (the ids
   the person has their own value of) has none."
  [tenant-spec team own-ids]
  (filterv #(and (required-in? % team)
                 (not (contains? own-ids (:id %))))
           (spec/credentials tenant-spec)))

(defn- names-text
  [names]
  (if (< (count names) 2)
    (first names)
    (str (string/join ", " (butlast names)) " and " (last names))))

(defn missing-message
  "Why work in the team can't start: names the team and the credentials."
  [team-name missing-names]
  (str "Team " team-name " requires your own " (names-text missing-names)
       (if (< 1 (count missing-names)) " credentials" " credential")
       ". Add " (if (< 1 (count missing-names)) "them" "it")
       " under Accounts, then start again; team and tenant values don't count."))
