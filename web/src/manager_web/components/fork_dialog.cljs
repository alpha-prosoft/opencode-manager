(ns manager-web.components.fork-dialog
  "The dialog that forks a session. Open it with

     [::fork-dialog/open session]

   A fork lands in one of the person's own teams, which they choose here:
   it belongs to that team and works with that team's credentials where the
   person has none of their own."
  (:require ["@mui/material/Button" :default Button]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogContentText" :default DialogContentText]
            ["@mui/material/DialogTitle" :default DialogTitle]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.roles :as roles]
            [manager-web.session :as session]
            [re-frame.core :as rf]
            [web.primitives.components :refer [RawAlert RawStack]]))

(defn team-options
  "[id name] of the teams a person may fork into: those they are a member of."
  [me]
  (mapv (fn [{:keys [id]}] [id (roles/team-name me id)])
        (roles/member-teams me)))

(defn default-team
  "The team chosen when the dialog opens: the person's only team, else the
   session's when it is one of theirs, else none."
  [me session]
  (let [ids
        (mapv :id (roles/member-teams me))]
    (if (= 1 (count ids))
      (first ids)
      (some #{(:team-id session)} ids))))

(defn request
  [me session]
  {:session (select-keys session [:id :name])
   :team-id (default-team me session)})

(defn can-fork?
  [me {:keys [team-id]}]
  (boolean (some #(= team-id (first %)) (team-options me))))

(defn fork-spec
  [session-id new-id team-id]
  {:id [:fork-session session-id]
   :error ::error
   :command {:cmd-id :fork-session :id new-id :attrs {:source-id session-id :team-id team-id}}
   :refetch-on-failure [:my-credentials]
   :navigate [:session {:session-id (str new-id)}]
   :on-success [::close]})

(defn intro
  [{:keys [name]}]
  (str "You get your own copy of \"" name "\", private to you. The original stays as it is."))

(defn team-help
  "Where the copy will live and whose credentials it will run with."
  [me team-id]
  (if team-id
    (str "The copy belongs to " (roles/team-name me team-id) " and runs with your credentials: "
         (roles/credential-order me team-id "your") ".")
    "Choose the team the copy belongs to. It runs with your own credentials where set, else that team's."))

(def no-team
  "You are not a member of a team, so there is nowhere to fork into. Ask a team admin or your tenant admin to add you to one.")

(rf/reg-event-db
 ::open
 (fn [db [_ session]]
   (-> db
       (assoc ::request (request (::session/me db) session))
       (data/clear-error ::error))))

(rf/reg-event-db
 ::choose-team
 (fn [db [_ team-id]]
   (assoc-in db [::request :team-id] team-id)))

(rf/reg-event-db
 ::close
 (fn [db _]
   (dissoc db ::request)))

(rf/reg-event-fx
 ::submit
 (fn [{:keys [db]} _]
   (let [{:keys [session team-id] :as request}
         (::request db)]
     (when (can-fork? (::session/me db) request)
       {:fx [[:dispatch [::data/run (fork-spec (:id session) (random-uuid) team-id)]]]}))))

(rf/reg-sub ::request (fn [db _] (::request db)))

(defn dialog
  []
  (let [request
        @(rf/subscribe [::request])

        me
        @(rf/subscribe [::session/me])

        busy?
        @(rf/subscribe [::data/busy? [:fork-session (get-in request [:session :id])]])

        error
        @(rf/subscribe [::data/error ::error])

        options
        (team-options me)

        close
        #(when-not busy? (rf/dispatch [::close]))]
    [:> Dialog {:open (some? request) :on-close close :maxWidth "xs" :fullWidth true
                :data-testid (el/id els/fork-dialog)}
     (when request
       [:<>
        [:> DialogTitle "Fork this session"]
        [:> DialogContent
         [RawStack {:spacing 2}
          [:> DialogContentText (intro (:session request))]
          (if (seq options)
            [layout/select {:label "Team"
                            :required true
                            :fullWidth true
                            :size "medium"
                            :id (el/id els/fork-dialog-team)
                            :disabled busy?
                            :value (:team-id request)
                            :options options
                            :option-el-id els/fork-dialog-team-option
                            :helperText (team-help me (:team-id request))
                            :on-change #(rf/dispatch [::choose-team %])}]
            [RawAlert {:severity "info" :el-id els/fork-dialog-no-team} no-team])
          [layout/error-alert error els/fork-dialog-error]]]
        [:> DialogActions
         [:> Button {:on-click close :disabled busy? :data-testid (el/id els/fork-dialog-cancel)} "Cancel"]
         [:> Button {:on-click #(rf/dispatch [::submit]) :variant "contained" :loading busy?
                     :disabled (not (can-fork? me request))
                     :data-testid (el/id els/fork-dialog-submit)}
          "Fork session"]]])]))
