(ns incremental.single
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.java.shell :as shell]
            [dorothy.core :as dotty])
  (:import (java.awt Color)
           (javax.swing JOptionPane ImageIcon)))

(def seconds 1000)
(def minutes (* 60 seconds))

(defmacro try-or [& forms]
  `(or ~@(map (fn [f] `(try ~f (catch Exception _#))) forms)))

(defn spit-edn [file data]
  (spit file (with-out-str (pprint/pprint data))))

(defn slurp-edn [file]
  (edn/read-string (slurp file)))

(def incremental-dir (io/file "."))
(def config-file (io/file incremental-dir "config.edn"))

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


(defn git-clean? [dir]
  (empty? (:out (shell/sh "git" "status" "-s" :dir dir))))

(defn git-obliterate! [dir]
  (println "This would obliterate " dir)
  (comment (shell/sh "git" "reset" "--hard" :dir dir)
           (shell/sh "git" "clean" "-fd" :dir dir)))

(defn git-stash! [dir]
  (shell/sh "git" "add" "." :dir dir)
  (shell/sh "git" "stash" :dir dir))

(defn git-last-commit [dir]
  (-> (shell/sh "git" "log" "-n" "1" "--format=format:%at" :dir dir)
      :out Long/parseLong (* seconds) (try-or 0)))

(defn image-url [name]
  (io/resource (str "png/" (dorothy.lookup/emoji-lookup name) ".png")))

(defn show-message! [dir]
  (let [dialog (-> (str "...but you've been working on " dir " for a while.")
                   (JOptionPane. JOptionPane/INFORMATION_MESSAGE
                                 JOptionPane/DEFAULT_OPTION
                                 (ImageIcon. (image-url :warning)))
                   (.createDialog "Sorry to interrupt..."))]
    (.setAlwaysOnTop dialog true)
    (.setVisible dialog true)))

(defn new-age [dir age]
  (if (git-clean? dir)
    0
    (min age (- (System/currentTimeMillis) (git-last-commit dir)))))

(defn in-window [age delay]
  #(< (first %) age (+ (first %) delay)))

(def actions {:stash git-stash!
              :obliterate git-obliterate!
              :warn show-message!})

(defn action! [directory age dot delay]
  (let [config (try-or (slurp-edn config-file))             ;; TODO move config up, and re-use previous config if new config fails to load
        todo (filter (in-window age delay) (:minutes config))]
    (doseq [action todo]
      ((actions action) directory))
    (let [limit (* (-> config :hue :minutes) minutes)
          proportion (- 1.0 (/ (min limit age) limit))
          [hue-start hue-end] (-> config :hue :range)
          hue (+ hue-end (* proportion (- hue-start hue-end)))
          color (Color/getHSBColor (float hue) (float 1.0) (float 1.0))]
      (dotty/paint dot color))))

(defn -main [directory id & _]

  (println "Started." directory id)

  ;(try-or (UIManager/setLookAndFeel (UIManager/getSystemLookAndFeelClassName)))

  (when-not (.exists config-file) (spit-edn config-file default-config))

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
