(ns incremental.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell]
            [dorothy.core :as dotty])
  (:import (java.awt Color)))


(def seconds 1000)
(def minutes (* 60 seconds))

(defn try-slurp [f]
  (try (slurp f)
       (catch Exception _ nil)))

(def incremental-dir (io/file (System/getProperty "user.home") ".incremental"))
(def instruction-file (io/file incremental-dir "instructions"))
(def ingestion-file (io/file incremental-dir "ingestion"))
(def dirs-file (io/file incremental-dir "dirs.edn"))
(def config-file (io/file incremental-dir "config.edn"))

(defn load-config [config-file default]
  (or (or (try-slurp config-file)
          (when-not (.exists config-file) (spit config-file (pr-str default))))
      default))

(def default-config {:popup {:message "Double or quits?"
                             :minutes 15}
                     :hue {:range [0.4 0]
                           :minutes 29}
                     :flash-minutes 29
                     :stash-minutes 30
                     :obliterate-minutes :never})

(defn load-state [edn-file now]
  (let [map->set #(into #{} (keys %))
        set->map #(into {} (for [k %] [k now]))]
    (-> edn-file
        try-slurp
        edn/read-string
        (or #{})
        set->map
        atom
        (add-watch :write #(spit edn-file (with-out-str (pprint/pprint (map->set %4))))))))

(defn git-clean? [dir]
  (empty? (:out (shell/sh "git" "status" "-s" :dir dir))))

(defn git-obliterate! [dir]
  (shell/sh "git" "reset" "--hard" :dir dir)
  (shell/sh "git" "clean" "-fd" :dir dir))

(defn git-stash! [dir]
  (shell/sh "git" "add" "." :dir dir)
  (shell/sh "git" "stash" :dir dir))

(defn git-last-commit [dir]
  (* (Long/parseLong (:out (shell/sh "git" "log" "-n" "1" "--format=format:%at" :dir dir))) seconds))

(defn check-directories [state]
  (into {} (for [[dir timestamp] state]
             [dir (if (git-clean? dir)
                    (System/currentTimeMillis)
                    (max timestamp (git-last-commit dir)))])))

;; TODO: configuration - add dot menu for this? or Command line instructions?
(defn -main [& _]

  (println "Started.")

  (let [config (atom default-config)
        state (load-state dirs-file (System/currentTimeMillis))
        running (atom true)
        dot (dotty/make-dot "Incremental")]

    (while @running

      (let [now (System/currentTimeMillis)]

        (println "Looping...")

        ;; read config
        (swap! config #(load-config config-file %))

        ;; ingest instructions
        (when (.exists instruction-file)
          (.renameTo instruction-file ingestion-file)
          (doseq [[_ op-code data] (re-seq #"(?m)^([-+!]{1}) (.+)$" (or (try-slurp ingestion-file) ""))]
            (println ">" op-code data)
            (case op-code
              "+" (swap! state assoc data (System/currentTimeMillis))
              "-" (swap! state dissoc data)
              "!" (reset! running false)
              (println "Invalid instruction:" op-code)))
          (io/delete-file ingestion-file :failed))

        ;; check directories
        (swap! state check-directories)

        (let [oldest (apply max (cons 0 (map (comp #(- now %) second) @state)))
              limit (* 15 minutes)
              proportion (- 1.0 (/ (min limit oldest) limit))
              hue (* 0.4 proportion)
              color (Color/getHSBColor (float hue) (float 1.0) (float 1.0))]
          (dotty/paint dot color)))

      ;; Thread/sleep
      (Thread/sleep (* 10 seconds)))

    (dotty/destroy dot))

  (println "Exiting...")
  (System/exit 0))

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
