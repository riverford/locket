(ns locket.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [re-frame.loggers :as loggers]
            [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.cofx :as cofx]
            [re-frame.fx :as fx]))

(defn id->key
  "Takes an (maybe namespaced) keyword and returns a new keyword, as follows
   e.g. :products -> :products/state
        :products/recommended -> :products.recommended/state"
  [k id]
  (if (namespace id)
    (keyword (str (namespace id) "." (name id)) (name k))
    (keyword (name id) (name k))))

(def id->state-key
  (partial id->key :state))

(def id->transitions-key
  (partial id->key :transitions))

(def id->interceptor-key
  (partial id->key :interceptor))

(defn states
  "Returns all the states of a state machine"
  [state-machine]
  (let [{:keys [transitions]} state-machine]
    (into #{} (map first transitions))))

(defn transitions
  "Returns all the transition events in a state machine.
   If a state is provided as a second argument, returns all the transitions from that state"
  ([state-machine]
   (let [{:keys [transitions]} state-machine]
     (into #{} (comp (map second) cat (map first)) transitions)))
  ([state-machine state]
   (let [{:keys [transitions]} state-machine]
     (into #{} (map first) (get transitions state)))))

(defn interceptor
  "Generic interceptor for state machine interactions.
   Calls path-fn on event-v of the event to find the path. "
  [state-machine]
  (let [{:keys [id initial-state path-fn debug?]} state-machine]
    (re-frame/->interceptor
      :id (id->interceptor-key id)
      :before (fn [context]
                (let [{db :db, event-v :event} (get context :coeffects)
                      [event & args] event-v
                      path (path-fn event-v)
                      current-state (get-in db path initial-state)
                      new-state (get-in state-machine [:transitions current-state event])]
                  (when debug?
                    (loggers/console
                      :log
                      (str path " • [" (or current-state "<nil>") ", " (or event "<nil>") "] "
                           (if (nil? new-state)
                             "-x No transition found"
                             (str "-> " new-state)))))
                  (if (nil? new-state)
                    (assoc context :queue nil)
                    (assoc-in context [:coeffects :db] (assoc-in db path (or new-state current-state))))))
      :after (fn [context]
               (if (and (get-in context [:coeffects :db])
                        (not (get-in context [:effects :db])))
                 (assoc-in context [:effects :db] (get-in context [:coeffects :db]))
                 context)))))

(defn add-handlers!
  "Adds all the state machine handlers for transitions"
  [state-machine]
  (let [events (transitions state-machine)
        interceptor (interceptor state-machine)]
    (doseq [event events
            :let [old-interceptors (registrar/get-handler :event event)]]
      (if old-interceptors
        ;; insert the state machine transition before the handler
        (let [new-interceptors (concat (butlast old-interceptors)
                                       [interceptor (last old-interceptors)])]
          (re-frame/clear-event event)
          (events/register event new-interceptors))
        (events/register event [cofx/inject-db fx/do-fx interceptor])))))

(defn register-subscriptions!
  "Registers a subscription for the current state of the state machine,
   as well as the currently available transitions.

   Calls path-fn on the query-v of the subscription to find the path."
  [state-machine]
  (let [{:keys [id path-fn subscriptions initial-state]} state-machine
        sub-state (id->state-key id)
        sub-transitions (id->transitions-key id)]
    (re-frame/reg-sub
      sub-state
      (fn [db query-v]
        (let [path (path-fn query-v)]
          (get-in db path initial-state))))
    (re-frame/reg-sub
      sub-transitions
      (fn [db query-v]
        (let [path (path-fn query-v)
              state (get-in db path initial-state)]
          (transitions state-machine state))))))

(defn ensure-path-fn
  "Ensure the state-machine has a path-fn, by constructing it from the id if not provided."
  [state-machine]
  (let [{:keys [id path-fn]} state-machine]
    (cond-> state-machine
      (nil? path-fn) (assoc :path-fn (constantly [(id->state-key id)])))))

(re-frame/reg-fx
  :locket/install-state-machine
  (fn [state-machine]
    (let [state-machine (ensure-path-fn state-machine)]
      (add-handlers! state-machine)
      (register-subscriptions! state-machine))))

(re-frame/reg-fx
  :locket/install-state-machines
  (fn [state-machines]
    (doseq [state-machine state-machines
            :let [state-machine (ensure-path-fn state-machine)]]
      (add-handlers! state-machine)
      (register-subscriptions! state-machine))))
