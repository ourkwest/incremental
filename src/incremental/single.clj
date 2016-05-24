(ns incremental.single
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell]
            [dorothy.core :as dotty])
  (:import (java.awt Color)
           (javax.swing JOptionPane)))

(def seconds 1000)
(def minutes (* 60 seconds))


(defmacro try-or [default & forms]
  `(try ~@forms (catch Exception e# ~default)))

(defn try-slurp [f]
  (try (slurp f)
       (catch Exception _ nil)))

(defn spit-edn [file data]
  (spit file (with-out-str (pprint/pprint data))))

(def incremental-dir (io/file (System/getProperty "user.home") ".incremental"))
(def instruction-file (io/file incremental-dir "instructions"))
(def ingestion-file (io/file incremental-dir "ingestion"))
(def dirs-file (io/file incremental-dir "dirs.edn"))
(def config-file (io/file incremental-dir "config.edn"))

(defn load-config [config-file default]
  (or (edn/read-string
        (or (try-slurp config-file)
            (when-not (.exists config-file) (spit-edn config-file default))))
      default))

(def default-config {:popup {:message "Double or quits?"
                             :minutes 15}
                     :hue {:range [0.4 0]
                           :minutes 29}
                     :flash-minutes 29
                     :stash-minutes 30
                     :obliterate-minutes :never})

(def default-config {:hue {:range [0.4 0]
                           :minutes 29}
                     :minutes {2 "Hello!"
                               29 :flash
                               30 :stash}})

(defn new-state [timestamp]
  {:start timestamp :done -1})

(defn load-state [edn-file now]
  (let [map->set #(into #{} (keys %))
        set->map #(into {} (for [k %] [k (new-state now)]))]
    (-> edn-file
        try-slurp
        edn/read-string
        (or #{})
        set->map
        atom
        (add-watch :write #(spit-edn edn-file (map->set %4))))))

(defn git-clean? [dir]
  (empty? (:out (shell/sh "git" "status" "-s" :dir dir))))

(defn git-obliterate! [dir]
  (shell/sh "git" "reset" "--hard" :dir dir)
  (shell/sh "git" "clean" "-fd" :dir dir))

(defn git-stash! [dir]
  (shell/sh "git" "add" "." :dir dir)
  (shell/sh "git" "stash" :dir dir))

(defn git-last-commit [dir]
  (try-or 0 (-> (shell/sh "git" "log" "-n" "1" "--format=format:%at" :dir dir)
                :out Long/parseLong (* seconds))))

(defn new-age [dir age]
  (if (git-clean? dir)
    0
    (min age (- (System/currentTimeMillis) (git-last-commit dir)))))

(defn action! [directory age dot delay]
  ;;; TODO
  )

(defn -main [directory id & _]

  (println "Started." directory id)

  (let [dot (dotty/make-dot directory)
        stop-file (io/file "local" "stop" id)]

    (loop [old-age 0]

      (when (.exists? stop-file)
        (io/delete-file stop-file :silently)
        (println "Exiting...")
        (System/exit 0))

      (let [age (new-age directory old-age)
            delay (* 10 seconds)]

        (action! directory age dot delay)

        (Thread/sleep delay)
        (recur (+ age delay)))))


  ; loop
  ; check if its time to stop
  ;   stop gracefully
  ; check git status
  ;



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
