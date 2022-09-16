(ns dart-sass-clj.core
  (:refer-clojure :exclude [compile])
  (:require [axle.core :as watch]
            [cinch.core :as util]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.stacktrace :as err]
            [clojure.tools.cli :as cli])
  (:import [de.larsgrefer.sass.embedded SassCompiler SassCompilerFactory SassCompilationFailedException]
           de.larsgrefer.sass.embedded.importer.ClasspathImporter
           java.io.File
           java.net.URL
           [org.webjars NotFoundException WebJarAssetLocator]
           [sass.embedded_protocol
            EmbeddedSass$OutboundMessage$CompileResponse$CompileSuccess
            EmbeddedSass$OutputStyle]))

(def ^:private prefix-re
  #"META-INF/resources/webjars/(.+?)/.+?/(.+)")

(defn- prefix-path
  [url]
  (when-let [[_ webjar path] (re-matches prefix-re url)]
    (str webjar "/" path)))

(defn- get-prefix-map
  "Matches sass4clj webjar import semantics for compatibility."
  [^WebJarAssetLocator locator]
  (->> locator
       (.listAssets)
       (map (juxt prefix-path identity))
       (into {})))

(def ^:private webjar-re
  #"META-INF/resources/webjars/(.+?)/(.+)")

(defn- webjar-map
  "Handles webjar imports, direct and transitive."
  [^WebJarAssetLocator locator url]
  (when-let [[_ webjar path] (re-find webjar-re url)]
    (try
      (.getFullPath locator webjar path)
      (catch NotFoundException e))))

(defn- ^ClasspathImporter sass-importer
  "Returns an overloaded ClasspathImporter that conforms webjar import
  statements to the same semantics as sass4clj. See here:

  https://github.com/Deraen/sass4clj#import-load-order"
  []
  (let [locator    (WebJarAssetLocator.)
        prefix-map (get-prefix-map locator)]
    (proxy [ClasspathImporter] []
      (canonicalize [^String url from]
        (let [url* (or (prefix-map url) url)
              ^ClasspathImporter this this]
          (proxy-super canonicalize url* from)))

      (canonicalizeUrl [^String url]
        (let [url* (or (webjar-map locator url) url)
              ^ClasspathImporter this this]
          (proxy-super canonicalizeUrl url*))))))

(defn- register-webjar-importer
  [^SassCompiler compiler]
  (->> (sass-importer)
       (.autoCanonicalize)
       (.registerImporter compiler))
  compiler)

(def ^:private output-style
  {:expanded   EmbeddedSass$OutputStyle/EXPANDED
   :compressed EmbeddedSass$OutputStyle/COMPRESSED})

(defn- set-output-style
  [^SassCompiler compiler {s   :output-style
                           :or {s :compressed}}]
  (->> s
       (output-style)
       (.setOutputStyle compiler))
  compiler)

(defn- set-source-maps
  [^SassCompiler compiler {s :source-maps}]
  (when s
    (.setGenerateSourceMaps compiler s))
  compiler)

(defn compiler
  "Returns a compiler instance. Config options:

  :output-style  - Either :expanded or :compressed,
                   default [:compressed]
  :source-maps   - Whether to generate source maps, boolean,
                   default [false]"
  [config]
  (-> (SassCompilerFactory/bundled)
      (set-output-style config)
      (set-source-maps config)
      (register-webjar-importer)))

(defn- ->url
  [x]
  (cond
    (instance? File x) (.toURL ^File x)
    (instance? URL x)  x))

(defn- log
  [& xs]
  (apply println "dart-sass-clj -" xs))

(defn ^EmbeddedSass$OutboundMessage$CompileResponse$CompileSuccess compile-result
  "Compiles file returns CompileSuccess obj.
  Ex: (io/resource META-INF/resources/webjars/bootstrap/5.1.3/scss/bootstrap.scss)"
  [^SassCompiler compiler file]
  (some->> file
           (->url)
           (.compile compiler)))

(defn compile-str
  "Compiles sass string to css string."
  [^SassCompiler compiler s]
  (->> s
       (.compileScssString compiler)
       (.getCss)))

(defn- main-file?
  [^File file]
  (->> file
       (.getName)
       (re-find #"^[^_].+\.(scss|sass|css)$")))

(defn- css-file?
  [p]
  (re-find #".*\.(scss|sass|css)$" p))

(defn relativize
  [^String path ^File file]
  (-> path
      (io/file)
      (.getCanonicalFile)
      (.toURI)
      (.relativize (.toURI (.getCanonicalFile file)))
      (.toString)))

(defn- relativize-project
  [file]
  (->> file
       (io/file)
       (relativize (System/getProperty "user.dir"))))

(defn- log-compile
  [src-dir files]
  (some->> files
           (map (fn [^File f] (.getName f)))
           (seq)
           (log "Compiling in" (relativize-project src-dir)))
  files)

(defn- convert-extension
  [p]
  (str/replace p #"\.(scss|sass)$" ".css"))

(defn- link-sm
  [^File css-file css]
  (->> (str (.getName css-file) ".map")
       (format "\n\n/*# sourceMappingURL=%s */")
       (str css)))

(defn- get-css-file
  [{t-dir :target-dir
    s-dir :source-dir} ^File sass-file]
  (->> sass-file
       (relativize s-dir)
       (convert-extension)
       (io/file t-dir)))

(defn- get-sm-file
  [^File css-file]
  (-> (.getPath css-file)
      (str ".map")
      (io/file)))

(defn- emit-css!
  [{:keys [source-maps]
    :as   config} ^File css-file css]
  (io/make-parents css-file)
  (cond->> css
    source-maps (link-sm css-file)
    true        (spit css-file)))

(defn- emit-source!
  [{s-dir :source-dir} ^File css-file source-file]
  (let [p   (.getParent css-file)
        out (->> source-file
                 (relativize s-dir)
                 (io/file p "scss"))]
    (io/make-parents out)
    (spit out (slurp source-file))
    out))

(defn- process-source
  [config css-file source-s]
  (or (some->> source-s
               (re-find #"^file:///.*$")
               (URL.)
               (io/file)
               (emit-source! config css-file)
               (relativize (.getParent css-file)))
      source-s))

(defn- process-sm
  [{t-dir :target-dir
    :as   config} css-file sm]
  (letfn [(f [sources]
            (map (partial process-source config css-file)
                 sources))]
    (-> sm
        (json/read-str)
        (assoc "file" (relativize t-dir css-file))
        (update "sources" f)
        (json/write-str :escape-slash false))))

(defn- emit-sm!
  [config css-file sm]
  (let [sm-file (get-sm-file css-file)]
    (io/make-parents sm-file)
    (->> (process-sm config css-file sm)
         (spit sm-file))))

(defn compile-source
  [{c           :compiler
    source-maps :source-maps
    :as         config} sass-file]
  (let [result   (compile-result c sass-file)
        css      (.getCss result)
        css-file (get-css-file config sass-file)]
    (emit-css! config css-file css)
    (when source-maps
      (->> (.getSourceMap result)
           (emit-sm! config css-file)))))

(defn- compile-sources
  [config]
  (doseq [s (:source-dirs config)]
    (let [c (assoc config :source-dir s)]
      (->> s
           (io/file)
           (file-seq)
           (filter main-file?)
           (log-compile s)
           (map (partial compile-source c))
           (doall)))))

(defn- on-compile-error
  [config e]
  (if (:watch config)
    (->> e
         (.getMessage)
         (str "\n")
         (log "SASS compile error:"))
    (throw e)))

(defn- on-error
  [config e]
  (if (:watch config)
    (do (->> e
             (.getMessage)
             (str "\n")
             (log "SASS unhandled error:"))
        (err/print-cause-trace e))
    (throw e)))

(defn- try-compile-sources
  [config]
  (try
    (compile-sources config)
    (catch SassCompilationFailedException e
      (on-compile-error config e))
    (catch Exception e
      (on-error config e))))

(defn- overflow?
  [{t :type}]
  (when (= t :overflow)
    (log "Warning: watcher overflow event")
    true))

(defn- recompile?
  [event]
  (or (overflow? event)
      (css-file? (:path event))))

(defn compile-handler
  [config]
  (fn [_ events]
    (when (some recompile? events)
      (try-compile-sources config))))

(defn compile
  "Compiles sass source to css targets.

  :target-dir    - Directory to output css forest
  :source-dirs   - Source directories where to look for sass
                   source files
  :watch         - Boolean, start watch process if true,
                   Compile once and return if false
  :compiler      - [Optional] SassCompiler to use for
                   compilation, if not provided, a default one
                   will be made

  Options passed to the default compiler:

  :output-style  - Either :expanded or :compressed,
                   default [:compressed]
  :source-maps   - Whether to generate source maps, boolean,
                   default [false]"
  [{c      :compiler
    t-dir  :target-dir
    s-dirs :source-dirs
    watch  :watch
    :or    {c      (compiler config)
            t-dir  "target/"
            s-dirs (util/get-source-dirs)}
    :as    config}]
  (let [config* (->> {:compiler    c
                      :target-dir  t-dir
                      :source-dirs s-dirs}
                     (merge config))]
    (try-compile-sources config*)
    (when watch
      (log "Watching sass dirs...")
      (watch/watch!
       {:paths   s-dirs
        :handler (->> (compile-handler config*)
                      (watch/window 50))})
      {:runway/block true})))

;; Main

(def cli-opts
  [["-t" "--target-dir TARGET_DIR" "Where to output compiled css and source maps"
    :id :target-dir
    :default "target/"]
   ["-s" "--source-dirs SOURCE_DIRS" "A list of directories to search for sass main files"
    :id :source-dirs
    :parse-fn edn/read-string]
   ["-o" "--output-style OUTPUT_STYLE" "Either :expanded or :compressed to reduce file size"
    :id :output-style
    :parse-fn edn/read-string
    :default :compressed]
   ["-w" "--watch" "Watch source directories for changes in sass"
    :id :watch]
   [nil "--source-maps" "Emits source map for each compiled css output file"
    :id :source-maps]
   ["-h" "--help"]])

(defn cli-args
  [args]
  (cli/parse-opts args cli-opts))

(defn -main
  [& args]
  (let [{:keys [options summary]} (cli-args args)]
    (if (:help options)
      (println summary)
      (let [r (compile options)]
        (when (= r {:runway/block true})
          @(promise))))))
