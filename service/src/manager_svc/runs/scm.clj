(ns manager-svc.runs.scm
  "Pull requests on Bitbucket Cloud and GitHub. A provider is a map

     {:git-username :git-host :token
      :open-pull-requests (fn [repository]) :pull-request (fn [repository number])
      :diff (fn [repository number]) :comment (fn [repository number content path line])
      :create-pull-request (fn [repository source destination title description draft])}

   Pull requests are {:id :title :author :source-branch :source-commit
   :destination-branch :url :clone-url}. Tests put a factory
   (fn [provider-name token] provider) under :runs/scm in the system map."
  (:require [clojure.string :as string]
            [manager-svc.http :as http])
  (:import [java.net URI]))

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

(def providers
  {"bitbucket" bitbucket
   "github" github})

(defn provider
  [system provider-name token]
  (let [factory
        (or (:runs/scm system)
            (fn [n t]
              (if-let [make (get providers n)]
                (make t)
                (throw (ex-info (str "Unknown SCM provider " n) {:permanent true})))))]
    (factory (name provider-name) token)))
