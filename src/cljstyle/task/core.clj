(ns cljstyle.task.core
  "Core cljstyle task implementations."
  (:require
    [cljstyle.config :as config]
    [cljstyle.format.core :as format]
    [cljstyle.task.diff :as diff]
    [cljstyle.task.print :as p]
    [cljstyle.task.process :as process]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str])
  (:import
    java.io.File))


;; ## Utilities

(def ^:dynamic *suppress-exit*
  "Bind this to prevent tasks from exiting the system process."
  false)


(defn- exit!
  "Exit a task with a status code."
  [code]
  (if *suppress-exit*
    (throw (ex-info (str "Task exited with code " code)
                    {:code code}))
    (System/exit code)))


(defn- search-roots
  "Convert the list of paths into a collection of search roots. If the path
  list is empty, uses the local directory as a single root."
  [paths]
  (mapv io/file (or (seq paths) ["."])))


(defn- load-configs
  "Load parent configuration files. Returns a merged configuration map."
  [label ^File file]
  (let [configs (config/find-up file 25)]
    (if (seq configs)
      (p/logf "Using cljstyle configuration from %d sources for %s:\n%s"
              (count configs)
              label
              (str/join "\n" (mapcat config/source-paths configs)))
      (p/logf "Using default cljstyle configuration for %s"
              label))
    (apply config/merge-settings config/default-config configs)))


(defn- walk-files!
  "Walk source files and apply the processing function to each."
  [f paths]
  (->>
    (search-roots paths)
    (pmap (fn prep-root
            [^File root]
            (let [canonical (.getCanonicalFile root)]
              [(load-configs (.getPath root) canonical) root canonical])))
    (process/walk-files! f)))


(defn- write-stats!
  "Write stats output to the named file."
  [file-name stats]
  (let [ext (last (str/split file-name #"\."))]
    (case ext
      "edn"
      (spit file-name (prn-str stats))

      "tsv"
      (->> (:files stats)
           (into (dissoc stats :files)
                 (map (fn [[k v]]
                        [(keyword "files" (name k)) v])))
           (map (fn [[k v]] (str (subs (str k) 1) \tab v \newline)))
           (str/join)
           (spit file-name))

      ;; else
      (p/printerrf "Unknown stats file extension '%s' - ignoring!" ext))))


(defn- duration-str
  "Format a duration in milliseconds for human consumption."
  [elapsed]
  (cond
    ;; 100 ms
    (< elapsed 100)
    (format "%.2f ms" (double elapsed))

    ;; 1 second
    (< elapsed 1000)
    (format "%d ms" (int elapsed))

    ;; 1 minute
    (< elapsed (* 60 1000))
    (format "%.2f sec" (/ elapsed 1000.0))

    ;; any longer
    :else
    (let [elapsed-sec (/ elapsed 1000.0)
          minutes (long (/ elapsed-sec 60))
          seconds (long (rem elapsed-sec 60))]
      (format "%d:%02d" minutes seconds))))


(defn- report-stats
  "General result reporting logic."
  [results]
  (let [counts (:counts results)
        total-files (apply + (vals counts))
        diff-lines (apply + (keep :diff-lines (vals (:results results))))
        stats (cond-> {:files counts
                       :total total-files
                       :elapsed (:elapsed results)}
                (pos? diff-lines)
                (assoc :diff-lines diff-lines))]
    (p/log (pr-str stats))
    (when (or (p/option :report) (p/option :verbose))
      (printf "Checked %d files in %s\n"
              total-files
              (if-let [elapsed (:elapsed results)]
                (duration-str elapsed)
                "some amount of time"))
      (doseq [[type-key file-count] (sort-by val (comp - compare) (:files stats))]
        (printf "%6d %s\n" file-count (name type-key)))
      (when (pos? diff-lines)
        (printf "Resulting diff has %d lines\n" diff-lines))
      (flush))
    (when-let [stats-file (p/option :stats)]
      (write-stats! stats-file stats))))



;; ## Version Command

(def version
  "Project version string."
  (if-let [props-file (io/resource "META-INF/maven/mvxcvi/cljstyle/pom.properties")]
    (with-open [props-reader (io/reader props-file)]
      (let [props (doto (java.util.Properties.)
                    (.load props-reader))
            {:strs [groupId artifactId version revision]} props]
        (format "%s/%s %s (%s)"
                groupId artifactId version
                (str/trim-newline revision))))
    "HEAD"))


(defn print-version
  "Implementation of the `version` command."
  [args]
  (when (seq args)
    (binding [*out* *err*]
      (println "cljstyle version command takes no arguments")
      (flush)
      (exit! 1)))
  (println version)
  (flush))



;; ## Config Command

(defn print-config-usage
  "Print help for the config command."
  []
  (println "Usage: cljstyle [options] config [path]")
  (newline)
  (println "Show the merged configuration which would be used to format the file or")
  (println "directory at the given path. Uses the current directory if one is not given."))


(defn show-config
  "Implementation of the `config` command."
  [paths]
  (when (< 1 (count paths))
    (binding [*out* *err*]
      (println "cljstyle config command takes at most one argument")
      (flush)
      (exit! 1)))
  (let [^File file (first (search-roots paths))
        config (load-configs (.getPath file) file)]
    (pprint config)
    config))



;; ## Find Command

(defn print-find-usage
  "Print help for the find command."
  []
  (println "Usage: cljstyle [options] find [paths...]")
  (newline)
  (println "Search for files which would be checked for errors. Prints the relative")
  (println "path to each file."))


(defn- find-source
  "Print information about a single source file."
  [_ path _]
  {:type :found
   :info path})


(defn find-sources
  "Implementation of the `find` command."
  [paths]
  (let [results (walk-files! find-source paths)
        counts (:counts results)
        total (apply + (vals counts))]
    (p/logf "Searched %d files in %.2f ms"
            total
            (:elapsed results -1.0))
    (p/log (pr-str counts))
    results))



;; ## Check Command

(defn print-check-usage
  "Print help for the check command."
  []
  (println "Usage: cljstyle [options] check [paths...]")
  (newline)
  (println "Check source files for formatting errors. Prints a diff of all malformed lines")
  (println "found and exits with an error if any files have format errors."))


(defn- check-source
  "Check a single source file and produce a result."
  [config path ^File file]
  (let [original (slurp file)
        revised (format/reformat-file original config)]
    (if (= original revised)
      {:type :correct
       :debug (str "Source file " path " is formatted correctly")}
      (let [diff (diff/unified-diff path original revised)]
        {:type :incorrect
         :debug (str "Source file " path " is formatted incorrectly")
         :info (cond-> diff
                 (not (p/option :no-color))
                 (diff/colorize))
         :diff-lines (diff/count-changes diff)}))))


(defn check-sources
  "Implementation of the `check` command."
  [paths]
  (let [results (walk-files! check-source paths)
        counts (:counts results)]
    (report-stats results)
    (when-not (empty? (:errors results))
      (p/printerrf "Failed to process %d files" (count (:errors results)))
      (exit! 3))
    (when-not (zero? (:incorrect counts 0))
      (p/printerrf "%d files formatted incorrectly" (:incorrect counts))
      (exit! 2))
    (p/logf "All %d files formatted correctly" (:correct counts))
    results))



;; ## Fix Command

(defn print-fix-usage
  "Print help for the fix command."
  []
  (println "Usage: cljstyle [options] fix [paths...]")
  (newline)
  (println "Edit source files in place to correct formatting errors."))


(defn- fix-source
  "Fix a single source file and produce a result."
  [config path ^File file]
  (let [original (slurp file)
        revised (format/reformat-file original config)]
    (if (= original revised)
      {:type :correct
       :debug (str "Source file " path " is formatted correctly")}
      (do
        (spit file revised)
        {:type :fixed
         :info (str "Reformatting source file " path)}))))


(defn fix-sources
  "Implementation of the `fix` command."
  [paths]
  (let [results (walk-files! fix-source paths)
        counts (:counts results)]
    (report-stats results)
    (when-not (empty? (:errors results))
      (p/printerrf "Failed to process %d files" (count (:errors results)))
      (exit! 3))
    (if (zero? (:fixed counts 0))
      (p/logf "All %d files formatted correctly" (:correct counts))
      (p/printerrf "Corrected formatting of %d files" (:fixed counts)))
    results))



;; ## Pipe Command

(defn print-pipe-usage
  "Print help for the `pipe` command."
  []
  (println "Usage: cljstyle [options] pipe")
  (newline)
  (println "Reads from stdin and fixes formatting errors piping the results to stdout."))


(defn pipe
  "Implementation of the `pipe` command."
  []
  (let [cwd (System/getProperty "user.dir")
        config (load-configs cwd (io/file cwd))]
    (print (format/reformat-file (slurp *in*) config))
    (flush)))
