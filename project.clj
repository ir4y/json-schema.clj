(defproject json-schema "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :resource-paths ["resources" "JSON-Schema-Test-Suite/tests"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [cheshire "5.5.0"]]
  :profiles {:dev {:dependencies [[http-kit "2.1.19"]]}})
