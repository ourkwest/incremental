(ns incremental.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell])
  (:import (java.io File)))


(def seconds 1000)
(def minutes (* 60 seconds))

(defn try-slurp [f default]
  (try (slurp f)
       (catch Exception _ default)))

(def instruction-file (io/as-file (str (System/getProperty "user.home") File/separator "/.incremental/instructions")))
(def ingestion-file (io/as-file (str (System/getProperty "user.home") File/separator "/.incremental/ingestion")))
(def dirs-file (io/as-file ".incremental-dirs.edn"))
(def dirs (-> dirs-file
              (try-slurp "#{}")
              edn/read-string
              atom
              (add-watch :store
                         (fn [_ _ _ new-state]
                           (spit dirs-file (with-out-str (pprint/pprint new-state)))))))
(def state (atom {}))

(defn git-clean? [dir]
  (empty? (:out (shell/sh "git" "status" "-s" :dir dir))))

(defn git-last-commit [dir]
  (* (Long/parseLong (:out (shell/sh "git" "log" "-n" "1" "--format=format:%at" :dir dir))) seconds))

(defn last-change [dir]
  (fn [timestamp]
    (if timestamp
      (max timestamp (git-last-commit dir))
      (System/currentTimeMillis))))

(defn -main [& _]

  (println "Started.")
  (let [running (atom true)]
    (while @running

      (println "Looping...")

      ;; ingest instructions
      (when (.exists instruction-file)
        (println "Rename:" (.renameTo instruction-file ingestion-file))
        (doseq [[_ op-code data] (re-seq #"(?m)^([-+!]{1}) (.+)$" (try-slurp ingestion-file ""))]
          (case op-code
            "+" (swap! dirs conj data)
            "-" (swap! dirs disj data)
            "!" (reset! running false)
            (println "Invalid instruction:" op-code)))
        (println "Delete:" (io/delete-file ingestion-file :failed)))

      ;; check directories
      (doseq [dir @dirs]
        (if (git-clean? dir)
          (swap! state dissoc dir)
          (swap! state update dir (last-change dir))))

      (let [now (System/currentTimeMillis)]
        (doseq [[dir timestamp] @state]
          (println (- now timestamp) "\t" dir)))

      ;; Thread/sleep
      (Thread/sleep (* 10 seconds))

      ))

  (println "Stopped."))


; Bonus brownie recipe for reading the source. :-)
;
; (bake {:celcius 180}
;       (mix (melt {:dark-chocolate {:grams 125}
;                   :butter         {:grams 175}})
;            (whisk {:eggs         3
;                    :caster-sugar {:grams 275}})
;            {:plain-flour   {:grams 75}
;             :baking-powder {:tsps 1}}
;            (or chocolate-chips raisins nuts)))
