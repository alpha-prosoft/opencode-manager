(ns manager-svc.platform.tenants
  "Tenants the operator configured: MANAGER_TENANTS (JSON) or
   MANAGER_TENANTS_FILE (a .json or .edn file), each a list of

     {\"name\": \"acme\", \"onboarding\": \"file:///repos/acme-onboarding\"}

   The onboarding source is a location (a git URL, an https:// archive, an
   absolute path or a file:// directory) or a source map like a team's
   repository ({\"type\": \"git\", \"url\": ..., \"branch\": ...,
   \"credentials\": ...}); its tenant.json is at its root unless the source
   names a path. Operators register more tenants at runtime
   (:register-tenant).

   The file is read again when it changes (checked every
   MANAGER_CONFIG_RELOAD_SECONDS) or an operator asks (reload!). A file
   that fails to read or validate keeps the last good list. Tenants added
   to the file are synced right away (manager-svc.platform.reload); removed
   ones are no longer synced, their data stays and comes back with them."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [jsonista.core :as json]
            [manager-svc.git :as git]
            [manager-svc.names :as names]
            [manager-svc.platform.spec :as spec]
            [manager-svc.reload :as reload]))

(def ^:private archive-suffixes
  [".tar.gz" ".tgz" ".zip"])

(defn- local-directory?
  [location]
  (and (string/starts-with? location "file://")
       (not (.isDirectory (io/file (git/file-url->path location) ".git")))
       (.isDirectory (io/file (git/file-url->path location)))))

(defn- from-location
  [location]
  (cond
    (string/starts-with? location "/")
    {:type "path" :location location :path "."}

    (local-directory? location)
    {:type "path" :location (git/file-url->path location) :path "."}

    (some #(string/ends-with? location %) archive-suffixes)
    {:type "url" :url location :path "."}

    :else
    {:type "git" :url location :path "."}))

(defn source
  "{:value source :errors [...]} of an onboarding location or source map."
  [onboarding]
  (let [raw
        (cond
          (string? onboarding) (from-location (string/trim onboarding))
          (map? onboarding) (merge {:path "."} (update-keys onboarding keyword))
          :else nil)]
    (if (nil? raw)
      {:value nil :errors ["onboarding: should be a location or a source map"]}
      (spec/decode spec/team-source raw))))

(defn- name-errors
  [tenant-name]
  (cond
    (not (and (string? tenant-name) (re-matches names/identifier-pattern tenant-name)))
    [(str "name " (pr-str tenant-name) ": should be lowercase letters, digits and dashes")]

    (contains? spec/reserved-tenant-names tenant-name)
    [(str "name " tenant-name " names a page of the UI (/" tenant-name "); choose another name. Reserved: "
          (string/join ", " (sort spec/reserved-tenant-names)))]))

(defn tenant
  "{:value {:name :source} :errors [...]} of one tenant entry."
  [{:keys [name onboarding]}]
  (let [{source-value :value source-errors :errors}
        (source onboarding)

        errors
        (vec (concat (name-errors name) source-errors))]
    {:value (when (empty? errors) {:name name :source source-value})
     :errors errors}))

(defn- read-entries
  [{:keys [tenants tenants-file]}]
  (cond
    (not (string/blank? tenants-file))
    (let [text
          (slurp tenants-file)]
      (if (string/ends-with? (string/lower-case tenants-file) ".edn")
        (edn/read-string text)
        (json/read-value text json/keyword-keys-object-mapper)))

    (not (string/blank? tenants))
    (json/read-value tenants json/keyword-keys-object-mapper)

    :else
    []))

(defn configured
  "[{:name :source}] of the operator's configuration. Throws with every
   problem when an entry is invalid or a name repeats."
  [settings]
  (let [entries
        (mapv #(update-keys % keyword) (read-entries settings))

        results
        (map-indexed (fn [i entry] [i (tenant entry)]) entries)

        errors
        (vec (concat
              (for [[i {:keys [errors]}] results
                    error errors]
                (str "tenants." i "." error))
              (for [[tenant-name n] (frequencies (map :name entries))
                    :when (> n 1)]
                (str "tenant " tenant-name " is configured more than once"))))]
    (when (seq errors)
      (throw (ex-info (str "Invalid tenants (MANAGER_TENANTS, MANAGER_TENANTS_FILE): " (string/join "; " errors))
                      {:errors errors})))
    (mapv (comp :value second) results)))

(defn- tenants-cell
  [{:keys [tenants tenants-file config-reload-seconds] :as settings}]
  (let [file
        (when-not (string/blank? tenants-file) tenants-file)]
    (reload/cell [:tenants (if file nil tenants) file]
                 (fn []
                   (cond-> {:name (if file (str "Tenants file (" file ")") "MANAGER_TENANTS")
                            :load (fn [_] (configured settings))}
                     file (assoc :stamp (reload/file-stamp file)
                                 :check-ms (* 1000 (or config-reload-seconds 10))))))))

(defn current
  "[{:name :source}] of the operator's configuration as last loaded; throws
   when it never loaded."
  [settings]
  (reload/value! (tenants-cell settings)))

(defn reload!
  "Reads the tenants file again now: {:status :reloaded|:failed :error}."
  [settings]
  (reload/reload-now! (tenants-cell settings)))

(defn check-settings!
  [settings]
  (current settings))
