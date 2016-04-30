(ns incremental.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint])
  (:import (java.io File)))


(def seconds 1000)
(def minutes (* 60 seconds))


(defn try-slurp [f default]
  (try (slurp f)
       (catch Exception _ default)))

; TODO make them actual Files?
(def instruction-file (str (System/getProperty "user.home") File/separator "/.incremental/instructions"))
(def ingestion-file (str (System/getProperty "user.home") File/separator "/.incremental/ingestion"))
(def state-file ".incremental-state.edn")
(def state (-> state-file
               (try-slurp "#{}")
               edn/read-string
               atom
               (add-watch :store (fn [_ _ _ new-state]
                                   (spit state-file (with-out-str (pprint/pprint new-state)))))))


(defn -main [& _]

  (println "Started.")
  (let [running (atom true)]
    (while @running

      (println "Looping...")

      ;; ingest instructions

      (when (.exists (io/as-file instruction-file))
        (println "Rename:" (.renameTo (io/as-file instruction-file) (io/as-file ingestion-file)))
        (doseq [[_ code data] (re-seq #"(?m)^([-+!]{1}) (.+)$" (try-slurp ingestion-file ""))]
          (case code
            "+" (swap! state conj data)
            "-" (swap! state disj data)
            "!" (reset! running false)
            (println "Invalid instruction:" code)))
        (println "Delete:" (io/delete-file ingestion-file :failed)))


      ;; check directories

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
