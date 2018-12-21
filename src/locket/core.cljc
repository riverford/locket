(ns locket.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [re-frame.loggers :as loggers]
            [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.cofx :as cofx]
            [re-frame.fx :as fx]))


(defn states
  "Returns all the states from a state machine"
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
   Updates the state of the db in the given path"
  [state-machine]
  (let [{:keys [path debug? on-invalid-transition]} state-machine]
    (re-frame/->interceptor
      :id path
      :before (fn [context]
                (let [{:keys [db event]} (get context :coeffects)
                      [ev & args] event
                      current-state (get-in db path)
                      new-state (get-in state-machine [:transitions current-state ev])]
                  (if (and (nil? new-state) (contains? on-invalid-transition :warn))
                    (loggers/console :log (let [expected (transitions state-machine current-state)]
                                            (str "No transition found for event " ev " in state " current-state))))
                  (when (and debug? (some? new-state))
                    (loggers/console :log (str "[" (or current-state "<nil>") ", " ev "] -> " new-state)))
                  (if (and (nil? new-state) (contains? on-invalid-transition :prevent))
                    (assoc context :queue nil)
                    (assoc-in context [:coeffects :db] (assoc-in db path (or new-state current-state))))))
      :after (fn [context]
               (if (and (get-in context [:coeffects :db])
                        (not (get-in context [:effects :db])))
                 (assoc-in context [:effects :db] (get-in context [:coeffects :db]))
                 context)))))

(defn add-handlers
  "Adds all the state machine handlers for transitions"
  [state-machine]
  (let [{:keys [path]} state-machine
        evs (transitions state-machine)
        interceptor (interceptor state-machine)]
    (doseq [ev evs
            :let [old-interceptors (registrar/get-handler :event ev)]]
      (if old-interceptors
        ;; insert the state machine transition before the handler
        (let [new-interceptors (concat (butlast old-interceptors)
                                       [interceptor (last old-interceptors)])]
          (re-frame/clear-event ev)
          (events/register ev new-interceptors))
        (events/register ev [cofx/inject-db fx/do-fx interceptor])))))

(re-frame/reg-fx :locket/add-handlers add-handlers)

(re-frame/reg-event-fx
  :locket/install-state-machine
  (fn [cofx [event state-machine]]
    (let [{:keys [db]} cofx
          {:keys [path initial-state]} state-machine]
      {:locket/add-handlers state-machine
       :db (assoc-in db path initial-state)})))
