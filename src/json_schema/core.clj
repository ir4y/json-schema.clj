(ns json-schema.core
  (:refer-clojure :exclude [compile])
  (:require [cheshire.core :as json]
            [clojure.set]
            [clojure.string :as str]))

(declare compile)
(declare compile-registry)

(defn decode-json-pointer [x]
  (-> x (str/replace #"~0" "~")
      (str/replace #"~1" "/")
      (str/replace #"%25" "%")))

(defn num-comparator [a b]
  (cond (> a b) 1 (= a b) 0 :else -1))

(defn add-error [val-type ctx message]
  (let [k (or (get-in ctx [:config val-type]) :errors)]
    (update-in ctx [k] (fn [x] (if x (conj x {:path (:path ctx) :message message})
                                   [{:path (:path ctx) :message message}])))))

(defn add-deferred [ctx value annotation]
  (update-in ctx [:deferreds] conj {:path (:path ctx) :value value :deferred annotation}))

(defn reduce-indexed 
  "Reduce while adding an index as the second argument to the function"
  ([f coll]
   (reduce-indexed f (first coll) 0 (rest coll)))
  
  ([f init coll]
   (reduce-indexed f init 0 coll))
  
  ([f init ^long i coll]
   (if (empty? coll)
     init
     (let [v (first coll)
           fv (f init i v)]
       (recur f fv (inc i) (rest coll))))))

(defn compile-pointer [ref]
  (let [is-root-path (str/starts-with? ref "#")
        is-return-key (str/ends-with? ref "#")
        ref-path (->
                  ref
                  (str/replace #"(^#\/|#$)" "")
                  (str/split #"/")
                  (->>
                   (mapv (fn [x]
                           (if (re-matches #"^\d+$" x)
                             (read-string x)
                             (keyword (decode-json-pointer x)))))))]
    (if is-root-path
      (fn [ctx] (get-in (:doc ctx) ref-path))
      (if is-return-key
        (fn [ctx]
          (let [path (:path ctx)
                steps-back (first ref-path)
                ref-path (rest ref-path)
                absolute-path (concat (drop-last steps-back path) ref-path)]
            (last absolute-path)))
        (fn [ctx]
          (let [path (:path ctx)
                steps-back (first ref-path)
                ref-path (rest ref-path)
                absolute-path (concat (drop-last steps-back path) ref-path)]
            (get-in (:doc ctx) absolute-path)))))))

(defn compile-comparator
  [{name :name
    value-applicable? :applicable-value
    coerce-value      :coerce-value
    coerce-bound      :coerce-bound
    bound-applicable? :applicable-bound
    comparator-fn :comparator-fn
    message :message
    message-op :message-op
    exclusive :exclusive
    direction :direction
    bound :bound}]
  (fn [ctx v]
    (let [$bound (if (fn? bound) (bound ctx) bound)
          $bound (if (and (fn? coerce-bound) (some? $bound)) (coerce-bound $bound) $bound)
          $exclusive (if (fn? exclusive) (exclusive ctx) exclusive)
          op (if (= true $exclusive) < <=)]
      (cond
        (nil? $bound) ctx

        (and (some? $bound) (not (bound-applicable? $bound)))
        (add-error name ctx (str " could not compare with " $bound))

        (and (some? $exclusive) (not (boolean? $exclusive)))
        (add-error name ctx (str "exclusive flag should be boolean, got " $exclusive))

        (and (value-applicable? v)
               (bound-applicable? $bound)
               (not (op 0 (* direction (comparator-fn $bound (coerce-value v))))))
        (add-error name ctx (str "expected" message " " (coerce-value v) message-op $bound))
        :else ctx))))

(defn $data-pointer [x]
  (when-let [d (:$data x)] (compile-pointer d)))



(defn add-warning [ctx message]
  (update-in ctx [:warning] conj {:path (:path ctx) :message message}))

;; (def schema-type nil)
;; (def schema-key nil)

(defmulti schema-type (fn [k] k))

(defmulti schema-key (fn [k opts schema path regisry] (if (= :default k) :schemaDefault k)))

(defn build-ref [path]
  (str "#"
       (when (not (empty? path))
         (->> path
              (map (fn [x]
                     (cond
                       (string? x) x
                       (keyword? x) (subs (str x) 1)
                       :else (str x))))
              (str/join "/")
              (str "/")))))

(defn- compile-schema [schema path registry]
  (let [schema-fn (cond
                    (= true schema)
                    (fn [ctx v] ctx)

                    (= false schema)
                    (fn [ctx v] (add-error :schema ctx (str "schema is 'false', which means it's always fails")))

                    (map? schema)
                    (let [validators (doall
                                      (reduce (fn [acc [k v]]
                                                (let [v (or ($data-pointer v) v)] ;; check for $data
                                                  (if-let [vf (schema-key k v schema path registry)]
                                                    (conj acc vf)
                                                    acc)))
                                              [] (dissoc schema :title)))]
                      (fn [ctx v]
                        (let [pth (:path ctx)]
                          (reduce (fn [ctx vf] (vf (assoc ctx :path pth) v))
                                  ctx validators))))
                    :else
                    (fn [ctx v] (add-error :schema ctx (str "Invalid schema " schema))))]
    (let [ref (build-ref path)]
      (swap! registry assoc ref schema-fn))
    schema-fn))

(defmethod schema-type
  :string
  [_]
  (fn [ctx v]
    (if (not (or (string? v) (keyword? v)))
      (add-error :string ctx "expected type of string")
      (if (str/blank? (str/trim (name v)))
        (add-error :string ctx "expected not empty string")
        ctx))))


(defmethod schema-type
  :boolean
  [_]
  (fn validate-boolean [ctx v]
    (if (boolean? v)
      ctx
      (add-error :boolean ctx "expected boolean"))))


(def date-regexp #"^-?[0-9]{4}(-(0[1-9]|1[0-2])(-(0[0-9]|[1-2][0-9]|3[0-1]))?)?$")

(defmethod schema-type
  :date
  [_]
  (fn validate-date [ctx v]
    (if (not (string? v))
      (add-error :date ctx "date should be encoded as string")
      (if (re-matches date-regexp v)
        ctx
        (add-error :date ctx "wrong date format")))))

(defmethod schema-type
  :number
  [_]
  (fn [ctx v]
    (if (not (number? v))
      (add-error :date ctx "expected number")
      ctx)))

(def uri-regexp #"^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")

(defmethod schema-type
  :uri
  [_]
  (fn validate-uri [ctx v]
    (if (not (string? v))
      (add-error :uri ctx "uri should be encoded as string")
      (if (str/blank? v)
        (add-error :uri ctx "expected not empty string")
        (if (re-matches uri-regexp v)
          ctx
          (add-error :uri ctx "wrong uri format"))))))


(defmethod schema-type
  :integer
  [_]
  (fn [ctx v]
    (if (integer? v)
      ctx
      (add-error :integer ctx (str  "expected integer, got " v)))))

(def dateTime-regex #"^-?[0-9]{4}(-(0[1-9]|1[0-2])(-(0[0-9]|[1-2][0-9]|3[0-1])(T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?(Z|[+-]((0[0-9]|1[0-3]):[0-5][0-9]|14:00))?)?)?)?$")

(defmethod schema-type
  :datetime
  [_]
  (fn [ctx v]
    (if (not (string? v))
      (add-error :datetime ctx "datetime should be encoded as string")
      (if (re-matches dateTime-regex v)
        ctx
        (add-error :datetime  ctx "wrong datetime format")))))

(def time-regex #"^([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?$")

(defmethod schema-type
  :time
  [_]
  (fn [ctx v]
    (if (not (string? v))
      (add-error :time ctx "time should be encoded as string")
      (if (re-matches time-regex v)
        ctx
        (add-error :time ctx "wrong time format")))))

(def oid-regex #"^[[0-9]+\.]*$")

(defmethod schema-type
  :oid
  [_]
  (fn [ctx v]
    (if (not (string? v))
      (add-error :oid ctx "oid should be encoded as string")
      (if (re-matches oid-regex v)
        ctx
        (add-error :oid ctx "wrong oid format")))))

(def uuid-regex #"^([a-f\d]{8}(-[a-f\d]{4}){3}-[a-f\d]{12}?)$")

(defmethod schema-type
  :uuid
  [_]
  (fn [ctx v]
    (if (not (string? v))
      (add-error :uuid ctx "uuid should be encoded as string")
      (if (re-matches uuid-regex v)
        ctx
        (add-error :uuid ctx "wrong uuid format")))))

(def email-regex #"^[_A-Za-z0-9-\+]+(\.[_A-Za-z0-9-]+)*@[A-Za-z0-9-]+(\.[A-Za-z0-9]+)*(\.[A-Za-z]{2,})$")

(defmethod schema-type
  :email
  [_]
  (fn [ctx v]
    (if (not (string? v))
      (add-error :email ctx "email should be encoded as string")
      (if (re-matches email-regex v)
        ctx
        (add-error :email ctx "wrong email format")))))

(defmethod schema-type
  :object
  [_]
  (fn [ctx v]
    (if (map? v)
      ctx
      (add-error :object ctx "expected object"))))

(defmethod schema-type
  :array
  [_]
  (fn [ctx v]
    (if (sequential? v)
      ctx
      (add-error :array ctx "expected array"))))

(defmethod schema-type
  :null
  [_]
  (fn [ctx v]
    (if (nil? v)
      ctx
      (add-error :null ctx "expected null"))))

(defmethod schema-type
  :any
  [_]
  (fn [ctx v] ctx))

(defmethod schema-type
  nil 
  [_]
  (fn [ctx v]
    (if (nil? v)
      ctx
      (add-error :null ctx "expected null"))))

(defmethod schema-type
  :default
  [unknown]
  (fn [ctx v]
    (add-error :unknown-type ctx (str "Broken schema: unknown type " unknown))))


(defmethod schema-key
  :type
  [k opts schema path regisry] 
  (if (sequential? opts)
    (let [validators (doall (mapv (fn [o] (schema-type (keyword o))) opts))]
      (fn [ctx v]
        (if (some
             (fn [validator]
               (let [{err :errors} (validator (assoc ctx :errors []) v)]
                 (empty? err))) validators)
          ctx
          (add-error :type ctx (str "expected type of one of " (str/join ", " opts))))))
    (schema-type (keyword opts))))



(defmethod schema-key
  :properties
  [_ props schema path registry]
  (when (map? props)
    (let [props-validators
          (doall (reduce (fn [acc [k v]]
                           (assoc acc k (compile-schema v (into path [:properties (keyword k)]) registry)))
                         {} props))
          requireds (reduce (fn [acc [k v]]
                              (if (= true (:required v))
                                (conj acc k)
                                acc)) [] props)
          req-validator (when (not (empty? requireds))
                          (schema-key :required requireds schema path registry))]
      (fn [ctx v]
        (let [pth (:path ctx)
              ctx (if req-validator (req-validator ctx v) ctx)]
          (reduce (fn [ctx [k vf]]
                    (if-let [vv (get v k)]
                      (vf (assoc ctx :path (conj pth k)) vv)
                      ctx))
                  ctx props-validators))))))

(defmethod schema-key
  :maxProperties
  [_ bound schema path registry]
  (compile-comparator
   {:name :maxProperties 
    :applicable-value map?
    :coerce-value count
    :applicable-bound number?
    :comparator-fn num-comparator 
    :message " number of properties "
    :message-op " >= "
    :direction 1
    :bound bound}))

(defmethod schema-key
  :minProperties
  [_ bound schema path registry]
  (compile-comparator
   {:name :minProperties
    :applicable-value map?
    :coerce-value count
    :applicable-bound number?
    :comparator-fn num-comparator 
    :message " number of properties "
    :message-op " <= "
    :direction -1
    :bound bound}))

(defn is-divider? [v d]
  (when (and (number? v) (number? d))
    (re-matches #"^\d+(\.0)?$" (str (/ v d)))))

(defmethod schema-key
  :multipleOf
  [_ bound schema path registry]
  (cond
    (number? bound)
    (fn [ctx v]
      (if (and (number? v) (not (or (= 0 v) (is-divider? v bound))))
        (add-error :multipleOf ctx (str "expected " v " is multiple of " bound))
        ctx))
    (fn? bound)
    (fn [ctx v]
      (let [$bound (bound ctx)]
        (cond
          (nil? $bound) ctx

          (and (some? $bound) (not (number? $bound)))
          (add-error :multipleOf ctx (str "could not find multiple of " v " and " $bound))

          (and (number? v) (not (or (= 0 v) (is-divider? v $bound))))
          (add-error :multipleOf ctx (str "expected " v " is multiple of " $bound))

          :else ctx)))
    :else nil))

(defn json-compare [a b]
  (cond
    (and (string? a) (string? b))   (= a b)
    (and (keyword? a) (keyword? b)) (= a b)
    (and (string? a) (keyword? b)) (= a (name b))
    (and (keyword? a) (string? b)) (= (name a) b)
    :else (= a b)))

(defmethod schema-key
  :enum
  [_ enum schema path registry]
  (if (fn? enum)
    (fn [ctx v]
      (let [$enum (enum ctx)]
        (if-not (sequential? $enum)
          (if (nil? $enum)
            ctx
            (add-error :enum ctx (str "could not enum by " $enum)))
          (if-not (some (fn [ev] (json-compare ev v)) $enum)
            (add-error :enum ctx (str "expeceted one of " (str/join ", " $enum)))
            ctx))))
    (fn [ctx v]
      (if-not (some (fn [ev] (json-compare ev v)) enum)
        (add-error :enum ctx (str "expeceted one of " (str/join ", " enum)))
        ctx))))


(defmethod schema-key
  :constant
  [_ const schema path registry]
  (if (fn? const)
    (fn [ctx v]
      (let [$const (const ctx)]
        (if-not (json-compare $const v)
          (add-error :constant ctx (str "expeceted " $const ", but " v))
          ctx)))
    (fn [ctx v]
      (if-not (json-compare const v)
        (add-error :constant ctx (str "expeceted " const ", but " v))
        ctx))))

(defmethod schema-key
  :discriminator
  [_ prop schema path registry]
  (fn [ctx v]
    (if-let [tp (get v (keyword prop))]
      (if-let [validator (get @registry (str "#/definitions/" tp))]
        (validator ctx v)
        (add-error :discriminator ctx (str "Could not resolve #/definitions/" tp)))
      ctx)))

(defmethod schema-key
  :exclusiveProperties
  [_ ex-props schema path registry]
  (fn [ctx v]
    (if (map? v)
      (reduce
       (fn [ctx {props :properties required :required}]
         (let [num (select-keys v (mapv keyword props))]
           (cond
             (and (not required) (<= (count num) 1)) ctx
             (and required (= (count num) 1)) ctx

             (and required (= (count num) 0))
             (add-error :exclusiveProperties ctx (str "One of properties " (str/join ", " props) " is required"))

             (> (count num) 1)
             (add-error :exclusiveProperties ctx (str "Properties " (->> props (map name) (str/join ", ")) " are mutually exclusive"))

             :else ctx)
           )) ctx ex-props)
      ctx)))

(defmethod schema-key
  :dependencies
  [_ props schema path registry]
  (assert (map? props))
  (let [props-validators
        (doall (reduce (fn [acc [k v]]
                         (assoc acc k
                                (cond
                                  (string? v)
                                  (fn [ctx vv]
                                    (if-not (contains? vv (keyword v))
                                      (add-error :dependencies ctx (str v " is required"))
                                      ctx))

                                  (and (sequential? v) (every? string? v))
                                  (let [req-keys (map keyword v)]
                                    (fn [ctx vv]
                                      (if-not (every? #(contains? vv %) req-keys)
                                        (add-error :dependencies ctx (str req-keys " are required"))
                                        ctx)))

                                  (or (map? v) (boolean? v))
                                  (compile-schema v (conj path :dependencies k) registry)
                                  
                                  :else (fn [ctx v] ctx))))
                       {} props))]
    (fn [ctx v]
      (if-not (map? v)
        ctx
        (let [pth (:path ctx)]
          (reduce (fn [ctx [k vf]]
                    (if (contains? v k) 
                      (vf (assoc ctx :path (conj pth k)) v)
                      ctx))
                  ctx props-validators))))))

(defmethod schema-key
  :patternProperties
  [_ props schema path registry]
  (let [props-map
        (doall (reduce (fn [acc [k v]]
                         (assoc acc (re-pattern (name k)) (compile-schema v (conj path :patternProperties (name k)) registry)))
                       {} props))]
    (fn [ctx v]
      (if-not (map? v)
        ctx
        (let [pth (:path ctx)]
          (reduce
           (fn [ctx [k vv]]
             (let [k-str (name k)]
               (let [new-ctx (assoc ctx :path (conj pth k))]
                 (reduce
                  (fn [ctx [pat validator]]
                    (if (re-find pat k-str)
                      (validator new-ctx vv)
                      ctx))
                  ctx props-map))))
           ctx v))))))

(defmethod schema-key
  :patternGroups
  [_ props schema path registry]
  (let [props-map
        (doall (reduce (fn [acc [k {sch :schema :as g}]]
                         (assoc acc (re-pattern (name k))
                                (assoc g :schema (compile-schema sch (conj path :patternGroups) registry))))
                       {} props))]
    (fn [ctx v]
      (if-not (map? v)
        ctx
        (reduce
         (fn [ctx [pat {validator :schema min :minimum max :maximum}]]
           (let [ctx (reduce
                      (fn [ctx [k vv]]
                        (let [pth (:path ctx)
                              k-str (name k)
                              new-ctx (assoc ctx :path (conj pth k))]
                          (if (re-find pat k-str)
                            (-> (validator new-ctx vv)
                                (assoc :patternGroupCount (inc (:patternGroupCount ctx)))
                                (assoc :path pth))
                            ctx)))
                      (assoc ctx :patternGroupCount 0) v)]
             (cond
               (and (nil? min) (nil? max)) ctx
               (and (some? min) (and (< (:patternGroupCount ctx) min)))
               (add-error :patternGroups ctx (str "patternGroup expects number of matched props " (:patternGroupCount ctx) " > " min))

               (and (some? max) (and (> (:patternGroupCount ctx) max)))
               (add-error :patternGroups ctx (str "patternGroup expects number of matched props " (:patternGroupCount ctx) " < " max))

               :else ctx)))
         ctx props-map)))))

(defmethod schema-key
  :extends
  [_ props schema path registry]
  (let [validators (doall
                    (->>
                     (cond (sequential? props) props
                           (map? props) [props]
                           :else (assert false (str "Not impl extends " props)))
                     (mapv (fn [o] (compile-schema o (conj path :extends) registry)))))]
    (fn [ctx v]
      (let [pth (:path ctx)]
        (reduce (fn [ctx validator] (validator (assoc ctx :path pth) v))
                ctx validators)))))

(defmethod schema-key
  :allOf
  [_ options schema path registry]
  (let [validators (doall (mapv (fn [o] (compile-schema o (conj path :allOf) registry)) options))]
    (fn [ctx v]
      (let [pth (:path ctx)]
        (reduce (fn [ctx validator] (validator (assoc ctx :path pth) v))
                ctx validators)))))

(defmethod schema-key
  :switch
  [_ switch schema path registry]
  (let [clauses (->> switch
                     (mapv (fn [clause]
                             (assoc clause
                                    :compiled
                                    (cond-> {}
                                      (:if clause) (assoc :if (compile-schema (:if clause) path registry))
                                      (and (:then clause) (map? (:then clause)))
                                      (assoc :then (compile-schema (:then clause) path registry))))))
                     doall)]
    (fn [ctx v]
      (let [pth (:path ctx)]
        (loop [ctx ctx
               [cl & cls] clauses]
          (cond (nil? cl) ctx

                (:if cl)
                (let [if-validator (get-in cl [:compiled :if])
                      {errs :errors} (if-validator ctx v)]
                  (if (empty? errs)
                    (let [next-ctx (cond
                                    (= false (:then cl))
                                    (add-error :switch ctx (str "expected not matches " (:if cl)))

                                    (= true (:then cl))
                                    ctx

                                    (map? (:then cl))
                                    ;; todo handle continue
                                    ((get-in cl [:compiled :then]) ctx v)

                                    :else ctx)]
                      (if (:continue cl)
                        (recur next-ctx cls)
                        next-ctx))
                    (recur ctx cls)))

                (contains? cl :then)
                (cond
                  (= false (:then cl))
                  (add-error :switch ctx "switch failed - nothing matched")

                  (= true (:then cl))
                  ctx

                  (map? (:then cl))
                  (let [then-validator (get-in cl [:compiled :then])]
                    (then-validator ctx v)))

                :else (recur ctx cls)))))))

(defmethod schema-key
  :not
  [_ subschema schema path registry]
  (let [validator (compile-schema subschema (conj path :not) registry)]
    (fn [ctx v]
      (let [{errs :errors} (validator (assoc ctx :errors []) v)]
        (if (empty? errs)
          (add-error :not ctx (str "Expected not " subschema))
          ctx)))))

(defmethod schema-key
  :anyOf
  [_ options schema path registry]
  (let [validators (doall (mapv (fn [o] (compile-schema o (conj path :anyOf) registry)) options))]
    (fn [ctx v]
      (if (some (fn [validator]
                   (let [res (validator (assoc ctx :errors []) v)]
                     (empty? (:errors res))))
                 validators)
        ctx
        (add-error :anyOf ctx (str "Non alternatives are valid"))))))

(defmethod schema-key
  :oneOf
  [_ options schema path registry]
  (let [validators (doall (mapv (fn [o] (compile-schema o (conj path :oneOf) registry)) options))]
    (fn [ctx v]
      (loop [cnt 0
             res nil
             validators validators]
        (if (empty? validators)
          (if (= 1 cnt)
            (update ctx :deferreds (fn [x] (into x (or (:deferreds res) []))))
            (add-error :oneOf ctx (str "expeceted one of " options ", but no one is valid")))
          (let [new-res ((first validators) (assoc ctx :errors [] :deferreds []) v)]
            (if (empty? (:errors new-res))
              (if (> cnt 0)
                (add-error :oneOf ctx (str "expeceted one of " options ", but more then one are valid"))
                (recur (inc cnt) new-res (rest validators)))
              (recur cnt res (rest validators)))))))))

(defmethod schema-key
  :additionalProperties
  [_ ap {props :properties
         pat-props :patternProperties
         pat-g-props :patternGroups
         :as schema} path registry]
  (let [props-keys (set (keys props))
        pat-props-regex (->> (keys (or pat-props {}))
                             (concat (keys (or pat-g-props {})))
                             (map (fn [x] (re-pattern (name x)))))
        is-path-prop? (fn [k] (let [k-str (name k)] (some #(re-find % k-str) pat-props-regex)))]
    (cond
      (= false ap)
      (fn [ctx v]
        (if (map? v)
          (let [v-keys (set (keys v))
                extra-keys (clojure.set/difference v-keys props-keys)]
            (if-not (empty? extra-keys)
              (let [pth (:path ctx)]
                (reduce (fn [ctx k]
                          (if-not (is-path-prop? k)
                            (add-error :additionalProperties (assoc ctx :path (conj pth k)) "extra property")
                            ctx)
                          ) ctx extra-keys))
              ctx))
          ctx))

      (map? ap)
      (let [ap-validator (compile-schema ap (conj path :additionalProperties) registry)]
        (fn [ctx v]
          (if (map? v)
            (let [v-keys (set (keys v))
                  extra-keys (clojure.set/difference v-keys props-keys)]
              (if-not (empty? extra-keys)
                (let [pth (:path ctx)]
                  (reduce (fn [ctx k]
                            (if-not (is-path-prop? k)
                              (ap-validator (assoc ctx :path (conj pth k)) (get v k))
                              ctx)
                            ) ctx extra-keys))
                ctx))
            ctx)))

      :else (assert false (str "Ups do not know how to validate additionalProperties " ap)))))


(defn has-property? [v k]
  (and (contains? v (keyword k))
       #_(not (nil? (get v (keyword k))))))

(defmethod schema-key
  :required
  [_ props schema path registry]
  (cond
    (sequential? props)
    (fn [ctx v]
      (if (map? v)
        (let [pth (:path ctx)]
          (reduce (fn [ctx k]
                    (if (has-property? v k)
                      ctx
                      (add-error  :requred ctx (str "Property " (name k) " is required"))))
                  ctx props))
        ctx))

    (fn? props)
    (fn [ctx v]
      (let [$props (props ctx)]
        (cond
          (nil? $props) ctx

          (not (sequential? $props))
          (add-error :required ctx (str "expected array of strings, but " $props))

          (map? v)
          (let [pth (:path ctx)]
            (reduce (fn [ctx k]
                      (if (has-property? v k)
                        ctx
                        (add-error :required ctx (str "Property " (name k) " is required"))))
                    ctx $props))
          :else ctx)))))

(defmethod schema-key
  :patternRequired
  [_ props schema path registry]
  (cond
    (sequential? props)
    (let [re-props (set (map (fn [x] (re-pattern (name x))) props))]
      (fn [ctx v]
        (if-not (map? v)
          ctx
          (let [not-matched-pats (reduce
                                  (fn [left-parts k]
                                    (let [k-str (name k)]
                                      (reduce (fn [acc p]
                                                (if-not (re-find p k-str)
                                                  (conj acc p)
                                                  acc))
                                              #{} left-parts)))
                                  re-props (keys v))]
            (if-not (empty? not-matched-pats)
              (add-error :patternRequired ctx (str "no properites, which matches " not-matched-pats))
              ctx)))))))

;; handled in items
(defmethod schema-key
  :additionalItems
  [_ items schema path registry]
  nil)


(decode-json-pointer "#/tilda~0field")

(defn to-uri [x]
  (try (java.net.URI. x)
       (catch Exception e
         (throw (Exception. (str "unable to parse \"" (pr-str x) "\" as uri"))))))

(defn uri-and-fragment [x]
  (when (string? x)
    (when-let [uri (to-uri x)]
      (let [fragment (.getFragment ^java.net.URI uri)
            endpoint (if fragment
                       (subs x 0 (- (count x) (count fragment) 1))
                       x)]
        [endpoint (str "#" fragment)]))))

(comment
  (uri-and-fragment "http://x.y.z/rootschema.json#foo")
  (uri-and-fragment "http://x.y.z/rootschema.json")
  )

(defmethod schema-key
  :id
  [_ id schema path registry]
  (println "ID:" id))

(defmethod schema-key
  :$ref
  [_ r schema path registry]
  (let [r (decode-json-pointer r)]
    (if (str/starts-with? r "http")
      (let [[uri fragment] (uri-and-fragment r)]
        (when-let [cache (or (get @registry uri)
                           (when-let [res (try (slurp r) (catch Exception e))]
                             (let [remote-registry (compile-registry (json/parse-string res keyword))]
                               (swap! registry assoc uri remote-registry)
                               remote-registry)))]
          (when-let [validator (get @cache fragment)]
            (fn [ctx v]
              (let [res (validator ctx v)]
                (assoc ctx :errors (into (:errors ctx) (:errors res))))))))
      (fn [ctx v]
        (if-let [validator (get @registry r)]
          (validator ctx v)
          (add-error :$ref ctx (str "Could not resolve $ref " r)))))))

(defmethod schema-key
  :maximum
  [_ bound {ex :exclusiveMaximum} path registry]
  (let [ex (or ($data-pointer ex) ex)]
    (compile-comparator
     {:name :maximum
      :applicable-value number?
      :coerce-value identity
      :applicable-bound number?
      :comparator-fn num-comparator 
      :message ""
      :message-op " < "
      :exclusive ex
      :direction 1
      :bound bound})))

(defmethod schema-key
  :exclusiveMaximum
  [_ bound schema path registry]
  nil)


(defmethod schema-key
  :minimum
  [_ bound {ex :exclusiveMinimum} path registry]
  (let [ex (or ($data-pointer ex) ex)]
    (compile-comparator
     {:name :minimum
      :applicable-value number?
      :coerce-value identity
      :applicable-bound number?
      :comparator-fn num-comparator 
      :message ""
      :message-op " > "
      :exclusive ex
      :direction -1
      :bound bound})))


(defmethod schema-key
  :exclusiveMinimum
  [_ bound schema path registry]
  nil)


(defn string-utf8-length [x]
  (when (string? x)
    (.count (.codePoints ^java.lang.String x))))

(defmethod schema-key
  :maxLength
  [_ bound schema path registry]
  (compile-comparator
   {:name :maxLength
    :applicable-value string?
    :coerce-value string-utf8-length
    :applicable-bound number?
    :comparator-fn num-comparator 
    :message " string length "
    :message-op " < "
    :direction 1
    :bound bound}))

(defmethod schema-key
  :minLength
  [_ bound schema path registry]
  (compile-comparator
   {:name :minLength
    :applicable-value string?
    :coerce-value string-utf8-length
    :applicable-bound number?
    :comparator-fn num-comparator 
    :message " string length "
    :message-op " > "
    :direction -1
    :bound bound}))


(defmulti compile-format-coerce (fn [fmt] fmt))

(defmethod compile-format-coerce
  :date [_]
  identity)

(defmethod compile-format-coerce
  :datetime [_]
  identity)

(defmethod compile-format-coerce
  :time [_]
  (fn [v] (str/replace v #"(Z|[+-]\d+:\d+)$" ""))) 

(defmethod compile-format-coerce
  :default [_]
  identity) 
  
((compile-format-coerce :time)
 "13:15:17.000+01:00")

(defmethod schema-key
  :formatMaximum
  [_ bound {fmt :format ex :exclusiveFormatMaximum} path registry]
  (when-not (= "unknown" fmt)
    (let [ex (or ($data-pointer ex) ex)]
      (compile-comparator
       {:name :formatMaximum
        :applicable-value string?
        :coerce-value (compile-format-coerce (keyword fmt))
        :coerce-bound (compile-format-coerce (keyword fmt))
        :applicable-bound string?
        :comparator-fn compare
        :message " value "
        :message-op " <= "
        :direction 1
        :exclusive ex
        :bound bound}))))

(defmethod schema-key :exclusiveFormatMaximum [& _] nil)
(defmethod schema-key :exclusiveFormatMinimum [& _] nil)

(defmethod schema-key
  :formatMinimum
  [_ bound {fmt :format ex :exclusiveFormatMinimum} path registry]
  (when-not (= "unknown" fmt)
    (let [ex (or ($data-pointer ex) ex)]
      (compile-comparator
       {:name :formatMinimum
        :applicable-value string?
        :coerce-value (compile-format-coerce (keyword fmt))
        :coerce-bound (compile-format-coerce (keyword fmt))
        :applicable-bound string?
        :comparator-fn compare
        :message " value "
        :message-op " >= "
        :exclusive ex
        :direction -1
        :bound bound}))))

(defmethod schema-key
  :schemaDefault
  [_ bound schema path registry]
  ;; nop in json-schema
  )

(defmethod schema-key
  :uniqueItems
  [k uniq schema path registry]
  (cond
    (= true uniq)
    (fn [ctx v]
      (if (and (sequential? v) (not (= (count v) (count (set v)))))
        (add-error :uniqueItems ctx "expected unique items")
        ctx))

    (fn? uniq)
    (fn [ctx v]
      (let [$uniq (uniq ctx)]
        (cond
          (nil? $uniq) ctx

          (not (boolean? $uniq))
          (add-error :uniqueItems ctx (str "uniq flag ref should be boolean, but " $uniq))

          (= false $uniq) ctx

          (and (sequential? v) (not (= (count v) (count (set v)))))
          (add-error :uniqueItems ctx "expected unique items")

          :else ctx)))))

(defmethod schema-key
  :default
  [k subschema schema path registry]
  (println "Unknown schema" k ": " subschema " " path)
  (when (and (empty? path) (map? subschema))
    (compile-schema subschema (conj path k) registry))
  nil)

(defmethod schema-key
  :description
  [k desc schema path registry]
  ;; nop
  nil)

(defmethod schema-key
  :definitions
  [k subschema schema path registry]
  (when (map? subschema)
    (doseq [[k sch] subschema]
      (when (map? sch)
        (compile-schema sch (into path [:definitions (keyword k)]) registry)))))

(defmethod schema-key
  :maxItems
  [_ bound schema path registry]
  (compile-comparator
   {:name :maxItems
    :applicable-value sequential?
    :coerce-value count
    :applicable-bound number?
    :comparator-fn num-comparator 
    :message " array lenght "
    :message-op " >= "
    :direction 1
    :bound bound}))

(defmethod schema-key
  :minItems
  [_ bound schema path registry]
  (compile-comparator
   {:name :minItems
    :applicable-value sequential?
    :coerce-value count
    :applicable-bound number?
    :comparator-fn num-comparator 
    :message " array lenght "
    :message-op " <= "
    :direction -1
    :bound bound}))


(def format-regexps
  {"date-time" #"^(\d{4})-(\d{2})-(\d{2})[tT\s](\d{2}):(\d{2}):(\d{2})(\.\d+)?(?:([zZ])|(?:(\+|\-)(\d{2}):(\d{2})))?$"
   "date"      #"^(\d{4})-(\d{2})-(\d{2})$"
   "time"      #"^(\d{2}):(\d{2}):(\d{2})(\.\d+)?([zZ]|(\+|\-)(\d{2}):(\d{2}))?$"
   "email"     #"^[\w!#$%&'*+/=?`{|}~^-]+(?:\.[\w!#$%&'*+/=?`{|}~^-]+)*@(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,6}$"
   "hostname"  #"^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,6}$"
   "host-name"  #"^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,6}$"
   "ipv4"      #"^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
   "ipv6"      #"^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$"
   "color"     #"^(#(?:[0-9a-fA-F]{2}){2,3}|#[0-9a-fA-F]{3}|(?:rgba?|hsla?)\((?:\d+%?(?:deg|rad|grad|turn)?(?:,|\s)+){2,3}[\s\/]*[\d\.]+%?\)|black|silver|gray|white|maroon|red|purple|fuchsia|green|lime|olive|yellow|navy|blue|teal|aqua|orange|aliceblue|antiquewhite|aquamarine|azure|beige|bisque|blanchedalmond|blueviolet|brown|burlywood|cadetblue|chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|darkblue|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen|gainsboro|ghostwhite|gold|goldenrod|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki|lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|limegreen|linen|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue|mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|navajowhite|oldlace|olivedrab|orangered|orchid|palegoldenrod|palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|powderblue|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|skyblue|slateblue|slategray|slategrey|snow|springgreen|steelblue|tan|thistle|tomato|turquoise|violet|wheat|whitesmoke|yellowgreen|rebeccapurple)$"
   "ip-address" #"^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
   "uri"       #"^((https?|ftp|file):)//[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
   "uri-reference"    #".*"
   "uri-template"    #".*"
   "json-pointer"    #".*"
   "regex"    #"^.*$"
   "idn-hostname" #"^.*$"
   "iri-reference" #"^.*$"
   "iri" #"^.*$"
   "idn-email" #"^.*@.*$"
   "relative-json-pointer" #"^.*$"
   "unknownformat"   #"^.*$"
   "unknown"   #"^.*$"})

(defmethod schema-key
  :format
  [_ fmt schema path registry]
  (if (fn? fmt)
    (fn [ctx v]
      (if-let [$fmt (fmt ctx)]
        (if-let [regex (get format-regexps (if (keyword? $fmt) (name $fmt) $fmt))]
          (if (and (string? v) (not (re-find regex v)))
            (add-error :format ctx (str "expected format " $fmt))
            ctx)
          (add-error :format ctx (str "no format for " $fmt)))
        ctx))

    (let [regex (get format-regexps (name fmt))]
      (assert regex (str "no format for " fmt))
      (fn [ctx v]
        (if (and (string? v) (not (re-find regex v)))
          (add-error :format ctx (str "expected format " fmt))
          ctx)))))

(defmethod schema-key
  :pattern
  [_ fmt schema path registry]
  (cond
    (string? fmt)
    (let [regex (re-pattern fmt)]
      (fn [ctx v]
        (if (and (string? v) (not (re-find regex v)))
          (add-error :pattern ctx (str "expected format " fmt))
          ctx)))

    (fn? fmt)
    (fn [ctx v]
      (let [$fmt (fmt ctx)]
        (cond
          (nil? $fmt) ctx

          (not (or (string? $fmt) (keyword? $fmt)))
          (add-error :pattern ctx (str "could not interpret as pattern " $fmt))

          (and (string? v) (not (re-find (re-pattern (name $fmt)) v)))
          (add-error :pattern ctx (str "expected '" v "' matches pattern '" $fmt "'"))

          :else ctx)))))



(defmethod schema-key
  :contains
  [_ subsch schema path registry]
  (let [validator (compile-schema subsch path registry)]
    (fn [ctx v]
      (if (and (sequential? v)
               (not (some (fn [vv]
                            (let [{err :errors} (validator (assoc ctx :errors []) vv)]
                              (empty? err))
                            ) v)))
        (add-error :contains ctx (str "expected contains " subsch))
        ctx))))

(defmethod schema-key
  :subset
  [_ arr _ _ _]
  (fn [ctx v]
    (assert (or (fn? arr) (sequential? arr)))
    (let [arr (if (fn? arr) (arr ctx) arr)]
      (if (clojure.set/subset? (set v) (set arr))
        ctx
        (add-error :subset ctx (str v " is not a subset of " arr))))))

(defmethod schema-key
  :deferred
  [_ annotation schema path registry]
  (fn [ctx v]
    (add-deferred ctx v annotation)))

(defmethod schema-key
  :items
  [_ items {ai :additionalItems :as schema} path registry]
  (cond
    (or (map? items) (boolean? items))
    (let [validator (compile-schema items (conj path :items) registry)]
      (fn [ctx vs]
        (if-not (sequential? vs)
          ctx
          (if-not validator
            ctx
            (let [pth (:path ctx)]
              (reduce-indexed
               (fn [ctx idx v]
                 (validator (assoc ctx :path (conj pth idx)) v))
               ctx vs))))))

    (sequential? items)
    (let [validators (doall (map-indexed (fn [idx x]
                                           (if (or (map? x) (boolean? x))
                                             (compile-schema x (into path [:items idx]) registry)
                                             (assert false (pr-str "Items:" items)))) items))
          ai-validator (when-not (or (nil? ai) (boolean? ai)) (compile-schema ai path registry))]
      (fn [ctx vs]
        (if-not (sequential? vs)
          (add-error :items ctx "expected array")
          (let [pth (:path ctx)]
            (loop [idx 0
                   validators validators
                   vs vs
                   ctx ctx]

              (let [new-ctx (assoc ctx :path (conj pth idx))]
                (cond
                  (and (empty? vs) (empty? validators)) ctx
                  (and (not (empty? vs)) (= true ai)) ctx
                  (and (not (empty? vs))
                       (empty? validators)
                       (= false ai)) (add-error :items new-ctx "additional items not allowed")

                  (and (not (empty? vs))
                       (empty? validators)
                       ai-validator) 
                  (recur  (inc idx) [] (rest vs) (ai-validator new-ctx (first vs)))

                  (and (not (empty? vs))
                       (not (empty? validators))) 
                  (recur (inc idx) (rest validators) (rest vs)
                         ((first validators) new-ctx (first vs)))

                  :else ctx ;; not sure

                  )))))))

    :else (assert false (pr-str "(:" schema))))


(defn compile [schema & [ctx]]
  (let [vf (compile-schema schema [] (atom {}))
        ctx (merge {:path [] :errors [] :deferreds [] :warnings []} (or ctx {}))]
    (fn [v & [lctx]] (dissoc (vf (merge (assoc ctx :doc v) (or lctx {})) v)
                             :doc :path :config))))

(defn compile-registry [schema & [ctx]]
  (let [registry (atom {}) 
        vf (compile-schema schema [] registry)]
    registry))

(defn validate [schema value & [ctx]]
  (let [validator (compile schema ctx)]
    (validator value ctx)))



(comment

  (validate
   {:properties
    {:finalDate {:format "date", :formatMaximum {:$data "1/beforeThan"}},
     :beforeThan {}}}
   {:finalDate "2015-11-09", :beforeThan "2015-08-17"})

  (validate {:type :object
             :properties {:name {:type "string"}
                          :extra "filed"}
             :required [:email]}
            {:name 5}
            {:config {:additionalProperties :warnings}})

  (validate {:minimum 5} 3)

  (validate {:oneOf [{:type "integer"} {:minimum 2}]} 1.5)
  (keys
   @(compile-registry
     {:patternProperties {:.* {:type "object"
                               :properties {:name {:type "string"}}}}})
   )

  (validate {:constant 5} 5)
  (validate {:constant 5} 4)

  (validate {:constant 5} 4 {:config {:warnings {:additionalProperties true}}})


  (validate
   {:properties {:sameAs {:constant {:$data "1/thisOne"}}, :thisOne {}}}
   {:sameAs 5, :thisOne 6})


  (validate {:minimum 1.1, :exclusiveMinimum true} 1.1)
  (validate {:maximum 1.1, :exclusiveMaximum true} 1.1)
  (validate {:minimum 1.1, :exclusiveMinimum true} 1.0)

  (validate {:maximum 1.1, :exclusiveMaximum true} 1.2)

  (validate {:maximum 1.1} 1.1)
  (validate {:minimum 2} 1.1)

  (validate {:maximum 1} 1.1)

  (validate {:minimum 1.1} 1.1)

  (num-comparator 1.1 1.1)
  (num-comparator 1.2 1.1)

  (num-comparator 0.9 1.1)

  (compare "2014-12-03" "2015-08-17")

  (validate {:maxLength 2} "aaa")

  (validate {:maxLength 2} "aaa")

  (validate {:minimum 25} 20)

  (validate
   {:properties
    {:finalDate {:format "date", :formatMaximum {:$data "1/beforeThan"}},
     :beforeThan {}}}
   {:finalDate "2015-11-09", :beforeThan "2015-08-17"})


  (validate
   {:properties {:shouldMatch {}, :string {:pattern {:$data "1/shouldMatch"}}}}
   {:shouldMatch "^a*$", :string "abc"})

  (validate
   {:switch
    [{:if {:minimum 10}, :then {:multipleOf 2}, :continue true}
     {:if {:minimum 20}, :then {:multipleOf 5}}]}
   35)

  (validate {:description "positive integer <=1000 with one non-zero digit",
             :switch [{:if {:not {:minimum 1}} :then false}
                      {:if {:maximum 10} :then true}
                      {:if {:maximum 100} :then {:multipleOf 10}}
                      {:if {:maximum 1000} :then {:multipleOf 100}}
                      {:then false}]} 1001)


  (def vvv
    (compile {:type :object
               :properties {:name {:type "string"}
                            :email {:type "string"}}
               :additionalProperties false
               :required [:email]}))

  (vvv {:name "name" :email "email@ups.com" :extra "prop"}
       {:config {:additionalProperties :warnings}})

  (vvv {:name "name" :email "email@ups.com" :extra "prop"})

  (validate {:type :object
             :properties {:name {:type "string"}
                          :email {:type "string"}}
             :additionalProperties false
             :required [:email]}
            {:name "name" :email "email@ups.com" :extra "prop"}
            {:config {:additionalProperties :warnings}})

  )
