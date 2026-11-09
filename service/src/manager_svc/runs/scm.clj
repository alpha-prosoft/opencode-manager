(ns manager-svc.runs.scm
  "Pull requests on Bitbucket Cloud, GitHub and Gerrit (whose changes are
   handled as pull requests). A provider is a map

     {:git-username :git-host :token
      :open-pull-requests (fn [repository]) :pull-request (fn [repository number])
      :diff (fn [repository number]) :comment (fn [repository number content path line])
      :create-pull-request (fn [repository source destination title description draft])
      :review (fn [repository number {:keys [comments verdict]}])        optional
      :deliver (fn [repository change push!]) :update (fn [repository pr push!])  optional}

   Pull requests are {:id :title :author :source-branch :source-commit
   :destination-branch :url :clone-url}; :source-branch may be a ref
   (refs/changes/01/1/1). post-review!, open-change! and update-change! are
   what callers use: they take the provider's own :review, :deliver and
   :update when it has them, else comment by comment and branch by branch.
   push! is (fn [{:keys [ref trailer message amend]}] commit).

   A provider is made from the SCM source {:provider :repository :url
   :web-url} and the credential {:secret :username}. Tests put a factory
   (fn [source credential] provider) under :runs/scm in the system map."
  (:require [clojure.string :as string]
            [manager-svc.http :as http])
  (:import [java.net URI URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]))

(def max-redirects 3)

(defn- origin
  [url]
  (let [uri
        (URI/create url)]
    [(.getScheme uri) (.getHost uri) (.getPort uri)]))

(defn same-origin?
  [base url]
  (= (origin base) (origin url)))

(defn- url-of
  "The URL of path on base. An absolute URL (a pagination link) must stay on
   base, so the token never travels to another host."
  [base path]
  (if (string/starts-with? path "http")
    (if (same-origin? base path)
      path
      (throw (ex-info (str "Refusing to follow " path " away from " base) {:permanent true})))
    (str base path)))

(defn- redirect-target
  [url {:keys [status headers]}]
  (when (and (contains? #{301 302 303 307 308} status) (get headers "location"))
    (str (.resolve (URI/create url) ^String (get headers "location")))))

(defn- send-following!
  "Sends a request; GET redirects are followed while they stay on base."
  [base request]
  (loop [request
         request

         hops
         0]
    (let [response
          (http/request! request)

          target
          (when (= :get (:method request))
            (redirect-target (:url request) response))]
      (if (and target (< hops max-redirects))
        (recur (assoc request :url (url-of base target)) (inc hops))
        response))))

(defn- call
  [base headers method path & {:keys [params json accept]}]
  (let [url
        (str (url-of base path)
             (when (seq params) (str "?" (http/query-string params))))

        response
        (send-following! base (cond-> {:method method
                                       :url url
                                       :headers (cond-> headers accept (assoc "Accept" accept))}
                                json (assoc :json json)))]
    (http/checked! response (str (string/upper-case (name method)) " " path))))

(defn- bitbucket-pr
  [repository pr]
  {:id (:id pr)
   :title (:title pr)
   :author (get-in pr [:author :display_name])
   :source-branch (get-in pr [:source :branch :name])
   :source-commit (get-in pr [:source :commit :hash])
   :destination-branch (get-in pr [:destination :branch :name])
   :url (get-in pr [:links :html :href])
   :clone-url (str "https://bitbucket.org/" repository ".git")})

(defn bitbucket
  [token]
  (let [api
        "https://api.bitbucket.org/2.0"

        bb
        (partial call api {"Authorization" (str "Bearer " token)})]
    {:git-username "x-bitbucket-api-token-auth"
     :git-host "bitbucket.org"
     :token token
     :open-pull-requests
     (fn [repository]
       (loop [url (str "/repositories/" repository "/pullrequests")
              params {:state "OPEN" :pagelen 50}
              results []]
         (let [page
               (http/json-body (bb :get url :params params))

               collected
               (into results (map #(bitbucket-pr repository %)) (:values page))]
           (if-let [next-url (:next page)]
             (recur next-url nil collected)
             collected))))
     :pull-request
     (fn [repository number]
       (bitbucket-pr repository (http/json-body (bb :get (str "/repositories/" repository "/pullrequests/" number)))))
     :diff
     (fn [repository number]
       (:body (bb :get (str "/repositories/" repository "/pullrequests/" number "/diff"))))
     :comment
     (fn [repository number content path line]
       (let [body
             (cond-> {:content {:raw content}}
               (and path line) (assoc :inline {:path path :to line}))]
         (:id (http/json-body (bb :post (str "/repositories/" repository "/pullrequests/" number "/comments") :json body)))))
     :create-pull-request
     (fn [repository source destination title description draft]
       (let [body
             {:draft (boolean draft)
              :title title
              :description description
              :source {:branch {:name source}}
              :destination {:branch {:name destination}}
              :close_source_branch true}]
         (bitbucket-pr repository (http/json-body (bb :post (str "/repositories/" repository "/pullrequests") :json body)))))}))

(defn- github-pr
  [repository pr]
  {:id (:number pr)
   :title (:title pr)
   :author (get-in pr [:user :login])
   :source-branch (get-in pr [:head :ref])
   :source-commit (get-in pr [:head :sha])
   :destination-branch (get-in pr [:base :ref])
   :url (:html_url pr)
   :clone-url (or (get-in pr [:head :repo :clone_url])
                  (str "https://github.com/" repository ".git"))})

(defn github
  [token]
  (let [gh
        (partial call "https://api.github.com" {"Authorization" (str "Bearer " token)
                                                "Accept" "application/vnd.github+json"
                                                "X-GitHub-Api-Version" "2022-11-28"})

        pull-request
        (fn [repository number]
          (github-pr repository (http/json-body (gh :get (str "/repos/" repository "/pulls/" number)))))]
    {:git-username "x-access-token"
     :git-host "github.com"
     :token token
     :open-pull-requests
     (fn [repository]
       (loop [page 1
              results []]
         (let [batch
               (http/json-body (gh :get (str "/repos/" repository "/pulls")
                                   :params {:state "open" :per_page 100 :page page}))

               collected
               (into results (map #(github-pr repository %)) batch)]
           (if (< (count batch) 100)
             collected
             (recur (inc page) collected)))))
     :pull-request pull-request
     :diff
     (fn [repository number]
       (:body (gh :get (str "/repos/" repository "/pulls/" number) :accept "application/vnd.github.diff")))
     :comment
     (fn [repository number content path line]
       (let [response
             (if (and path line)
               (gh :post (str "/repos/" repository "/pulls/" number "/comments")
                   :json {:body content
                          :commit_id (:source-commit (pull-request repository number))
                          :path path
                          :line line
                          :side "RIGHT"})
               (gh :post (str "/repos/" repository "/issues/" number "/comments")
                   :json {:body content}))]
         (:id (http/json-body response))))
     :create-pull-request
     (fn [repository source destination title description draft]
       (github-pr repository
                  (http/json-body (gh :post (str "/repos/" repository "/pulls")
                                      :json {:title title
                                             :body description
                                             :head source
                                             :base destination
                                             :draft (boolean draft)}))))}))

(def xssi-prefix
  ")]}'")

(defn strip-xssi
  "Gerrit's JSON without the )]}' line it starts with against XSSI."
  [text]
  (let [body
        (str text)]
    (if (string/starts-with? body xssi-prefix)
      (subs body (count xssi-prefix))
      body)))

(defn- gerrit-json
  [response]
  (http/read-json (strip-xssi (:body response))))

(defn- encode
  [value]
  (string/replace (URLEncoder/encode (str value) StandardCharsets/UTF_8) "+" "%20"))

(defn- basic
  [username secret]
  (str "Basic " (.encodeToString (Base64/getEncoder) (.getBytes (str username ":" secret) StandardCharsets/UTF_8))))

(defn change-id
  "The Change-Id of the change the platform pushes for key (stable, so a
   repeated push updates the same change)."
  [key]
  (let [digest
        (.digest (MessageDigest/getInstance "SHA-1") (.getBytes (str key) StandardCharsets/UTF_8))]
    (apply str "I" (map #(format "%02x" (bit-and % 0xff)) digest))))

(def gerrit-votes
  {"APPROVE" 1
   "APPROVE_WITH_NITS" 0
   "REQUEST_CHANGES" -1})

(def ^:private change-options
  "&o=CURRENT_REVISION&o=DETAILED_ACCOUNTS")

(defn- trim-slashes
  [url]
  (string/replace (str url) #"/+$" ""))

(defn- gerrit-change
  [base web repository change]
  (let [revision
        (get-in change [:revisions (keyword (:current_revision change))])]
    {:id (:_number change)
     :title (:subject change)
     :author (or (get-in change [:owner :name]) (get-in change [:owner :username]))
     :source-branch (:ref revision)
     :source-commit (:current_revision change)
     :destination-branch (:branch change)
     :change-id (:change_id change)
     :url (str web "/c/" repository "/+/" (:_number change))
     :clone-url (str base "/a/" repository)}))

(defn gerrit
  "A self-hosted Gerrit at the source's :url (links to changes use :web-url
   when the browser reaches Gerrit elsewhere), with an HTTP password."
  [{:keys [url web-url]} {:keys [secret username]}]
  (let [base
        (trim-slashes url)

        web
        (trim-slashes (or web-url url))

        api
        (partial call base (when secret {"Authorization" (basic username secret)}))

        authenticated
        (if secret "/a" "")

        get-json
        (fn [path]
          (gerrit-json (api :get (str authenticated path))))

        change-path
        (fn [repository number]
          (str "/changes/" (encode repository) "~" number))

        query
        (fn [repository q start]
          (get-json (str "/changes/?q=" (encode (str q " project:" repository)) "&n=100&S=" start change-options)))

        change
        (fn [repository number]
          (gerrit-change base web repository
                         (get-json (str (change-path repository number) "?" (subs change-options 1)))))

        by-change-id
        (fn [repository id]
          (or (some->> (first (query repository (str "change:" id) 0)) (gerrit-change base web repository))
              (throw (ex-info (str "Gerrit has no change " id " in " repository) {}))))

        push-for
        (fn [push! target id message amend]
          (push! {:ref (str "refs/for/" target)
                  :trailer (str "Change-Id: " id)
                  :message message
                  :amend amend}))]
    {:git-username username
     :git-host (.getHost (URI/create base))
     :token secret
     :open-pull-requests
     (fn [repository]
       (loop [start 0
              results []]
         (let [page
               (query repository "status:open" start)

               collected
               (into results (map #(gerrit-change base web repository %)) page)]
           (if (:_more_changes (last page))
             (recur (count collected) collected)
             collected))))
     :pull-request change
     :diff
     (fn [repository number]
       (let [encoded
             (:body (api :get (str authenticated (change-path repository number) "/revisions/current/patch")))]
         (String. (.decode (Base64/getMimeDecoder) (string/trim (str encoded))) StandardCharsets/UTF_8)))
     :comment
     (fn [repository number content path line]
       (let [body
             (if (and path line)
               {:comments {(keyword path) [{:line line :message content}]}}
               {:message content})]
         (gerrit-json (api :post (str authenticated (change-path repository number) "/revisions/current/review") :json body))
         (str number)))
     :review
     (fn [repository number {:keys [comments verdict]}]
       (let [summary
             (string/join "\n\n" (keep #(when-not (:path %) (:content %)) comments))

             inline
             (reduce (fn [acc {:keys [path line content]}]
                       (update acc (keyword path) (fnil conj []) {:line line :message content}))
                     {}
                     (filter :path comments))

             body
             (cond-> {:message summary :comments inline}
               (contains? gerrit-votes verdict) (assoc :labels {:Code-Review (get gerrit-votes verdict)}))]
         (gerrit-json (api :post (str authenticated (change-path repository number) "/revisions/current/review") :json body))
         (str number)))
     :create-pull-request
     (fn [_repository _source _destination _title _description _draft]
       (throw (ex-info "Gerrit changes are pushed, not created" {:permanent true})))
     :deliver
     (fn [repository {:keys [key target title description]} push!]
       (let [id
             (change-id key)

             commit
             (push-for push! target id (str title "\n\n" description) false)]
         (assoc (by-change-id repository id) :commit commit)))
     :update
     (fn [repository pr push!]
       (let [commit
             (push-for push! (:destination-branch pr) (:change-id pr) nil true)]
         (assoc (change repository (:id pr)) :commit commit)))}))

(def providers
  {"bitbucket" (fn [_source {:keys [secret]}] (bitbucket secret))
   "github" (fn [_source {:keys [secret]}] (github secret))
   "gerrit" gerrit})

(defn provider
  "The provider of an SCM source {:provider :url :web-url} with the
   credential {:secret :username} (nil when there is none)."
  [system source credential]
  (let [factory
        (or (:runs/scm system)
            (fn [source credential]
              (if-let [make (get providers (name (:provider source)))]
                (make source credential)
                (throw (ex-info (str "Unknown SCM provider " (name (:provider source))) {:permanent true})))))]
    (factory source (or credential {}))))

(defn post-review!
  "Posts a review plan's comments not already posted; calls (record! key
   comment-id) for each. Returns the number posted."
  [provider repository number {:keys [comments verdict]} already record!]
  (let [pending
        (remove #(contains? already (:key %)) comments)]
    (cond
      (empty? pending)
      0

      (:review provider)
      (let [review-id
            ((:review provider) repository number {:comments pending :verdict verdict})]
        (doseq [{:keys [key]} pending]
          (record! key review-id))
        (count pending))

      :else
      (do
        (doseq [{:keys [key content path line]} pending]
          (record! key ((:comment provider) repository number content path line)))
        (count pending)))))

(defn open-change!
  "Pushes a new change and opens its pull request: change is {:key :branch
   :target :title :description :draft}. Returns the pull request with :commit."
  [provider repository {:keys [branch target title description draft] :as change} push!]
  (if-let [deliver (:deliver provider)]
    (deliver repository change push!)
    (let [commit
          (push! {:ref (str "refs/heads/" branch)})]
      (assoc ((:create-pull-request provider) repository branch target title description draft)
             :commit commit))))

(defn update-change!
  "Pushes the change onto an open pull request; returns it with :commit."
  [provider repository pr push!]
  (if-let [update (:update provider)]
    (update repository pr push!)
    (assoc pr :commit (push! {:ref (str "refs/heads/" (:source-branch pr))}))))
