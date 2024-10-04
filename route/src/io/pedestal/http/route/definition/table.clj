; Copyright 2024 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns io.pedestal.http.route.definition.table
  (:require [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.route.types :as types]
            [io.pedestal.http.route.definition :as route-definition]
            [io.pedestal.http.route.path :as path]))

(defn- error
  [{:keys [row original]} msg]
  (format "In row %s, %s\nThe whole route was: %s" (if row (str (inc row)) "") msg (or original "nil")))

(defn- syntax-error
  [ctx posn expected was]
  (error ctx (format "%s should have been %s but was %s." posn expected (or was "nil"))))

(defn- surplus-declarations
  [{:keys [remaining] :as ctx}]
  (error ctx (format "there were unused elements %s." remaining)))

(def ^:private known-options [:app-name :host :port :scheme :verbs])
(def ^:private default-verbs #{:any :get :put :post :delete :patch :options :head})

(defn- make-parse-context
  [opts row route]
  ;; This context is passed through many functions, with values from the route vector
  ;; added as new keys. At the end, the extra keys are dissoc'ed and the result is a route map.
  (let [ctx {:row       row                                 ; index (if known) into the route vectors (for errors)
             :original  route                               ; the route as provided
             :remaining route                               ; the remaining values in the route vector
             :verbs     default-verbs}]                     ; allowed verbs for this route
    (assert (vector? route) (syntax-error ctx "the element" "a vector" route))
    (merge ctx
           (select-keys opts known-options))))

(defn- take-next-pair
  [argname expected-pred expected-str ctx]
  (let [[param arg & more] (:remaining ctx)]
    (if (= argname param)
      (do
        (assert (expected-pred arg) (syntax-error ctx (str "the parameter after" argname) expected-str arg))
        (assoc ctx argname arg :remaining more))
      ctx)))

(defn- parse-path
  [ctx]
  (let [[path & more] (:remaining ctx)]
    (assert (string? path) (syntax-error ctx "the path (first element)" "a string" path))
    (-> ctx
        (merge (path/parse-path path))
        (assoc :path path :remaining more))))

(defn- parse-verb
  [ctx]
  (let [[verb & more] (:remaining ctx)
        known-verb (:verbs ctx default-verbs)]
    (assert (set? known-verb) (str "The verb set used in table route options *must* be a set.  Got: " known-verb))
    (assert (known-verb verb) (syntax-error ctx "the verb (second element)" (str "one of " known-verb) verb))
    (assoc ctx :method verb :remaining more)))

(defn- parse-handlers
  [ctx]
  (let [[handlers & more] (:remaining ctx)]
    (if (vector? handlers)
      (assert (every? #(satisfies? interceptor/IntoInterceptor %) handlers) (syntax-error ctx "the vector of handlers" "a bunch of interceptors" handlers))
      (assert (satisfies? interceptor/IntoInterceptor handlers) (syntax-error ctx "the handler" "an interceptor" handlers)))
    (let [original-handlers (if (vector? handlers) (vec handlers) [handlers])
          handlers          (mapv interceptor/interceptor original-handlers)]
      (assoc ctx :interceptors handlers
             :remaining more
             :last-handler (last original-handlers)))))

(def ^:private attach-route-name (partial take-next-pair :route-name keyword? "a keyword"))

(defn- parse-route-name
  [{:keys [route-name interceptors last-handler] :as ctx}]
  (if route-name
    ctx
    (let [last-interceptor   (some-> interceptors last)
          default-route-name (cond
                               (:name last-interceptor) (:name last-interceptor)
                               (symbol? last-handler) (route-definition/symbol->keyword last-handler)
                               :else nil)]
      (assert default-route-name (error ctx "the last interceptor does not have a name and there is no explicit :route-name."))
      (assoc ctx :route-name default-route-name))))

(defn- remove-empty-constraints
  [ctx]
  (apply dissoc ctx
         (filter #(empty? (ctx %)) [:path-constraints :query-constraints])))

(defn- parse-constraints
  [{:keys [constraints path-params] :as ctx}]
  (let [path-param? (fn [[k _]] (some #{k} path-params))
        [path-constraints query-constraints] ((juxt filter remove) path-param? constraints)]
    (-> ctx
        (update :path-constraints merge (into {} (map route-definition/capture-constraint path-constraints)))
        (update :query-constraints merge query-constraints)
        remove-empty-constraints)))

(def ^:private attach-constraints (partial take-next-pair :constraints map? "a map"))

(defn- attach-path-regex
  [ctx]
  (path/merge-path-regex ctx))

(defn- finalize
  [ctx]
  (assert (empty? (:remaining ctx)) (surplus-declarations ctx))
  (select-keys ctx route-definition/allowed-keys))

(defn- route-table-row
  [opts row route]
  (-> opts
      (make-parse-context row route)
      parse-path
      parse-verb
      parse-handlers
      attach-route-name
      parse-route-name
      attach-constraints
      parse-constraints
      attach-path-regex
      finalize))

(defn table-routes
  "Constructs table routes.

  The standard constructor is an options map and then a seq (a list or a set) of route vectors.

  The single parameter constructor looks for the first map as the options, then any other vectors
  are the routes.

  The options map may have keys :app-name, :host, :port, :scheme, and :verbs.  The first four
  set the corresponding route keys of the routes; the :verbs key specifies the allowed verbs for
  the routes."
  ([routes]
   (table-routes (or (first (filter map? routes)) {})
                 (filterv vector? routes)))
  ([opts routes]
   {:pre [(map? opts)
          (or (set? routes)
              (sequential? routes))]}
   (types/->RoutingFragmentImpl
     (if (sequential? routes)
       (map-indexed #(route-table-row opts %1 %2) routes)
       ;; When passed options and a set, we don't actually know the index numbers.
       ;; This only affects error reporting.
       (map #(route-table-row opts nil %) routes)))))

