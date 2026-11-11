(ns manager-web.site-shots
  "Product pictures for the documentation's landing page: whole pages as a
   person sees them, without marks, under docs/img. Taken on the seeded
   documentation stack after the guides, or on their own:

     cd web && clojure -M:guide site"
  (:require [clojure.java.io :as io]
            [manager-web.components.elements :as component-els]
            [manager-web.guide-seed :as seed]
            [manager-web.guide-shots :as shots]
            [manager-web.guide-support :as s]
            [manager-web.helpers :as h]
            [manager-web.run.elements :as run-els])
  (:import [com.microsoft.playwright Page]))

(def site-dir
  "../docs/img")

(def viewport
  [1200 760])

(defn- picture!
  [picture-name]
  (shots/shot! picture-name {:full true})
  (io/make-parents (io/file site-dir "x"))
  (io/copy (io/file shots/out-dir (str picture-name ".png"))
           (io/file site-dir (str picture-name ".png"))))

(defn- run-page!
  [run-id]
  (s/goto (str "/runs/" run-id))
  (h/wait-visible run-els/result 30000)
  (s/settle))

(defn shoot!
  []
  (let [^Page page
        (s/page)

        [width height]
        viewport]
    (.setViewportSize page width height)
    (try
      (s/as! "bob")
      (s/goto "?team=demo")
      (picture! "home")
      (run-page! seed/branch-review)
      (picture! "review-result")
      (run-page! seed/failing-change)
      (picture! "change-and-build")
      (s/goto "/demo")
      (h/click (component-els/workflow-start "demo" "requirements"))
      (h/wait-visible component-els/run-dialog)
      (s/settle)
      (picture! "start-dialog")
      (h/click component-els/run-dialog-cancel)
      (s/as! "alice")
      (s/goto "/admin?tab=usage")
      (picture! "usage")
      (finally
        (.setViewportSize page 1440 900)))))
