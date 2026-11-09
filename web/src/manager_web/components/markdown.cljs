(ns manager-web.components.markdown
  "Markdown written by agents, rendered as HTML. Agent output is untrusted
   (a prompt injection can control it), so raw HTML in the source is shown as
   text, images become their alt text, and the result is sanitized against a
   short allow-list: no styles, images, forms, frames, event handlers or
   non-http(s) links. Links open in a new tab without access to this page.
   Reagent sets HTML only when it is marked with unsafe-html, which this
   namespace does for what the sanitizer returned and for nothing else."
  (:require ["@mui/material/Box" :default Box]
            ["dompurify" :as dompurify]
            ["marked" :refer [Marked]]
            [goog.string :as gstring]
            [manager-web.el :as el]
            [manager-web.theme :as theme]
            [reagent.core :as r]))

(def ^:private renderer
  #js {:html (fn [^js token] (gstring/htmlEscape (.-text token)))
       :image (fn [^js token] (gstring/htmlEscape (.-text token)))
       :checkbox (fn [^js token] (if (.-checked token) "[x] " "[ ] "))})

(def ^:private parser
  (Marked. #js {:gfm true :async false :renderer renderer}))

(def allowed-tags
  ["a" "b" "blockquote" "br" "code" "del" "em" "h1" "h2" "h3" "h4" "h5" "h6" "hr" "i"
   "li" "ol" "p" "pre" "s" "strong" "table" "tbody" "td" "th" "thead" "tr" "ul"])

(def allowed-attributes
  ["href" "title" "align" "start"])

(def ^:private config
  #js {:ALLOWED_TAGS (clj->js allowed-tags)
       :ALLOWED_ATTR (clj->js allowed-attributes)
       :ALLOW_DATA_ATTR false
       :ALLOW_ARIA_ATTR false
       :ALLOWED_URI_REGEXP #"(?i)^(?:https?|mailto):"})

(defn- open-links-in-new-tab
  [^js node]
  (when (and (= "A" (.-tagName node)) (.hasAttribute node "href"))
    (.setAttribute node "target" "_blank")
    (.setAttribute node "rel" "noopener noreferrer")))

(defn purifier
  "A sanitizer bound to window, configured for agent markdown."
  [window]
  (let [factory
        (or (.-default dompurify) dompurify)

        ^js instance
        (factory window)]
    (.addHook instance "afterSanitizeAttributes" open-links-in-new-tab)
    instance))

(def ^:private browser-purifier
  (delay (purifier js/window)))

(defn html
  "Safe HTML for markdown text."
  ([text]
   (html @browser-purifier text))
  ([^js purifier text]
   (if (.-isSupported purifier)
     (.sanitize purifier (.parse parser (or text "")) config)
     (gstring/htmlEscape (or text "")))))

(defn markdown
  [text & [el-id]]
  [:> Box {:sx (:markdown-document theme/sx)
           :data-testid (el/id el-id)}
   [:div {:dangerouslySetInnerHTML (r/unsafe-html (html text))}]])
