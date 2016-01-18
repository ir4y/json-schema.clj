(ns json-schema.core-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [json-schema.core :refer :all]))


(defn filter-by-name [nm] (fn [fl] (re-matches nm (.getPath fl))))


(def re-filter #"^.*(max|min|type|properties).*") ;; enum

(deftest a-schema-test
  (doseq [test-file  (->> "draft4"
                          io/resource
                          io/file
                          file-seq
                          (filter (filter-by-name re-filter)))]
    (when (.isFile test-file)
      (let [test-case (json/parse-string (slurp (.getPath test-file)) keyword)]
        (doseq [scenario test-case]
          (testing (:description scenario)
            (doseq [test-item (:tests scenario)]
              (is (= (:valid test-item)
                     (validate (:schema scenario) (:data test-item)))
                  (pr-str (assoc test-item :schema (:schema scenario)))))))))))
