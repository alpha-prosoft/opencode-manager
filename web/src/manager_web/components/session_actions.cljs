(ns manager-web.components.session-actions
  "Open, fork, start, stop, lock and delete a session."
  (:require ["@mui/icons-material/CallSplit" :default CallSplitIcon]
            ["@mui/icons-material/DeleteOutlined" :default DeleteIcon]
            ["@mui/icons-material/LockOutlined" :default LockIcon]
            ["@mui/icons-material/PlayArrow" :default PlayArrowIcon]
            ["@mui/icons-material/Stop" :default StopIcon]
            ["@mui/material/Button" :default Button]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Tooltip" :default Tooltip]
            [manager-web.components.confirm :as confirm]
            [manager-web.components.elements :as els]
            [manager-web.components.layout :as layout]
            [manager-web.data :as data]
            [manager-web.el :as el]
            [manager-web.format :as format]
            [manager-web.roles :as roles]
            [manager-web.theme :as theme]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [web.primitives.components :refer [RawStack]]))

(defn command-spec
  "A ::data/run spec for a session command that re-fetches `refetch` after."
  [cmd-id session-id {:keys [error-id refetch]}]
  {:id [cmd-id session-id]
   :error error-id
   :command {:cmd-id cmd-id :id session-id}
   :refetch refetch})

(defn fork-spec
  [session-id new-id error-id]
  {:id [:fork-session session-id]
   :error error-id
   :command {:cmd-id :fork-session :id new-id :attrs {:source-id session-id}}
   :refetch-on-failure [:my-credentials]
   :navigate [:session {:session-id (str new-id)}]})

(defn fork-body
  "The three facts of a fork: where the copy lives, who sees it, whose
   credentials it runs with."
  [me {:keys [name team-id]}]
  (str "You get your own copy of \"" name "\". It stays in " (roles/team-name me team-id)
       " and is private to you. It runs with your credentials: " (roles/credential-order me team-id "your")
       ". The original stays as it is."))

(defn fork-request
  [me session error-id]
  {:title "Fork this session?"
   :body (fork-body me session)
   :confirm "Fork session"
   :color "primary"
   :run (fork-spec (:id session) (random-uuid) error-id)})

(rf/reg-event-fx
 ::fork
 (fn [{:keys [db]} [_ session error-id]]
   {:fx [[:dispatch [::confirm/ask (fork-request (:manager-web.session/me db) session error-id)]]]}))

(defn- delete-request
  [session opts]
  {:title "Delete this session?"
   :body (str "\"" (:name session) "\" and its workspace files will be deleted. The audit trail is kept.")
   :confirm "Delete session"
   :run (cond-> (command-spec :delete-session (:id session) opts)
          (:on-deleted opts) (assoc :navigate (:on-deleted opts)))})

(defn- lock-request
  [session opts]
  {:title "Lock this session?"
   :body "It stops, and nobody can continue in it, you included. Anyone who can see it can still read it and fork it to continue in their own copy. Locking can't be undone."
   :confirm "Lock session"
   :run (command-spec :lock-session (:id session) opts)})

(defn- icon-action
  [{:keys [title label icon color on-click el-id busy?]}]
  [:> Tooltip {:title title}
   [:span
    [:> IconButton (cond-> {:size "small" :aria-label label :on-click on-click :loading (boolean busy?)
                            :data-testid (el/id el-id)}
                     color (assoc :color color))
     [:> icon {:fontSize "small"}]]]])

(defn- fork-button
  [{:keys [title variant on-click busy? el-id]}]
  [:> Tooltip {:title title}
   [:span
    [:> Button {:size "small" :variant variant :startIcon (r/as-element [:> CallSplitIcon])
                :on-click on-click :loading busy? :data-testid (el/id el-id)}
     "Fork"]]])

(defn session-actions
  "opts: :error-id where failures show, :refetch load keys to refresh,
   :on-deleted navigate args after deleting, :prominent? for the session page,
   :hide-fork? when the page offers forking elsewhere."
  [session {:keys [prominent? error-id hide-fork?] :as opts}]
  (let [id
        (:id session)

        live?
        (format/session-live? (:status session))

        busy?
        (fn [cmd-id] @(rf/subscribe [::data/busy? [cmd-id id]]))

        run
        (fn [cmd-id] #(rf/dispatch [::data/run (command-spec cmd-id id opts)]))

        can-open?
        (:can-open session)

        variant
        (if prominent? "contained" "text")

        fork
        (fn [title variant]
          [fork-button {:title title :variant variant :busy? (busy? :fork-session)
                        :on-click #(rf/dispatch [::fork session error-id])
                        :el-id (els/session-action :fork id)}])]
    [RawStack {:direction "row" :spacing 0.5 :sx (:end-aligned-row theme/sx)}
     (if can-open?
       [layout/external-button {:href (:open-url session) :size "small" :variant variant
                                :el-id (els/session-action :open id)}
        "Open"]
       (when-not hide-fork?
         (fork "Continue in your own private copy, with your own credentials" variant)))
     (when (and prominent? can-open?)
       (fork "Make your own private copy to continue in" "text"))
     (when (and (not live?) can-open?)
       [icon-action {:title "Start the sandbox" :label "Start session" :icon PlayArrowIcon
                     :on-click (run :start-session) :busy? (busy? :start-session)
                     :el-id (els/session-action :start id)}])
     (when (and live? (:can-manage session))
       [icon-action {:title "Stop the sandbox. Files are kept; opening it starts it again." :label "Stop session"
                     :icon StopIcon :on-click (run :stop-session) :busy? (busy? :stop-session)
                     :el-id (els/session-action :stop id)}])
     (when (and prominent? (:is-owner session) (not (:locked-at session)))
       [icon-action {:title "Freeze this session so it can only be forked" :label "Lock session" :icon LockIcon
                     :on-click #(rf/dispatch [::confirm/ask (lock-request session opts)])
                     :el-id (els/session-action :lock id)}])
     (when (:can-manage session)
       [icon-action {:title "Delete session" :label "Delete session" :icon DeleteIcon :color "error"
                     :on-click #(rf/dispatch [::confirm/ask (delete-request session opts)])
                     :el-id (els/session-action :delete id)}])]))
