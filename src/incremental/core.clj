(ns incremental.core
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell]
            [dorothy.core :as dotty])
  (:import (java.awt Color)
           (javax.swing JOptionPane ImageIcon SwingUtilities)
           (java.time Instant)
           (java.io File)))

(def seconds 1000)
(def minutes (* 60 seconds))

(defn log [& things]
  (println (str (Instant/now)) (apply str things)))

(defmacro try-or [& forms]
  `(or ~@(map (fn [f] `(try ~f (catch Exception _#))) forms)))

(defn spit-edn [file data]
  (spit file (with-out-str (pprint/pprint data))))

(defn slurp-edn [file]
  (edn/read-string (slurp file)))

(def incremental-dir (io/file "."))
(def config-file (io/file incremental-dir "config.edn"))

(def default-config {:hue {:range [0.4 0]
                           :minutes 30}
                     :minutes {28 :flash
                               29 :warn
                               30 :stash}})

(defn git-clean? [dir]
  (empty? (:out (shell/sh "git" "status" "-s" :dir dir))))

(defn git-obliterate! [dir & _]
  (log "This would obliterate " dir)
  (comment (shell/sh "git" "reset" "--hard" :dir dir)
           (shell/sh "git" "clean" "-fd" :dir dir)))

(defn git-stash! [dir & _]
  (log "This would stash " dir)
  (comment (shell/sh "git" "add" "." :dir dir)
           (shell/sh "git" "stash" :dir dir)))

(defn git-last-commit [dir]
  (-> (shell/sh "git" "log" "-n" "1" "--format=format:%at" :dir dir)
      :out Long/parseLong (* seconds) (try-or 0)))

(defn image-url [name]
  (io/resource (str "png" File/separator (dorothy.lookup/emoji-lookup name) ".png")))

(defn show-message! [dir & _]
  (SwingUtilities/invokeLater
    #(let [dialog (-> (str "...but you've been working on " dir " for a while.")
                      (JOptionPane. JOptionPane/INFORMATION_MESSAGE
                                    JOptionPane/DEFAULT_OPTION
                                    (ImageIcon. (image-url :warning)))
                      (.createDialog "Sorry to interrupt..."))]
      (.setAlwaysOnTop dialog true)
      (.setVisible dialog true))))

(defn flash! [_ dot delay color]
  (let [period (* 0.5 seconds)]
    (future
      (loop [t delay
             f true]
        (if f
          (dotty/paint dot color)
          (dotty/paint dot :warning))
        (when (< period t)
          (Thread/sleep period)
          (recur (- t period) (not f)))))))

(defn new-age [dir age]
  (if (git-clean? dir)
    0
    (min age (- (System/currentTimeMillis) (git-last-commit dir)))))

(defn in-window [age delay]
  #(let [time (* (first %) minutes)]
    (<= age time (+ age (dec delay)))))

(def actions {:stash git-stash!
              :obliterate git-obliterate!
              :warn show-message!
              :flash flash!})

(defn action! [directory age dot delay config]
  (let [limit (* (-> config :hue :minutes) minutes)
        proportion (- 1.0 (/ (min limit age) limit))
        [hue-start hue-end] (-> config :hue :range)
        hue (+ hue-end (* proportion (- hue-start hue-end)))
        color (Color/getHSBColor (float hue) (float 1.0) (float 1.0))
        todo (map second (filter (in-window age delay) (:minutes config)))]
    (dotty/paint dot color)
    (doseq [action todo]
      (log action directory)
      ((actions action) directory dot delay color))))

(defn reload [current file]
  (try-or (slurp-edn file) current))

(defn -main [directory id & _]
  (log "Started." directory id)
  (when-not (.exists config-file) (spit-edn config-file default-config))
  (let [dot (dotty/make-dot directory)
        stop-file (io/file "local" "stop" id)]
    (loop [old-age 0
           config (reload default-config config-file)]
      (when (.exists stop-file)
        (io/delete-file stop-file :silently)
        (log "Exiting..." directory)
        (System/exit 0))
      (let [age (new-age directory old-age)
            delay (* 10 seconds)]
        (log age directory)
        (action! directory age dot delay config)
        (Thread/sleep delay)
        (recur (+ age delay)
               (reload config config-file))))))

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
