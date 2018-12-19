(ns locket.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
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
  (let [{:keys [id path initial-state modifiers]} state-machine]
    (re-frame/->interceptor
      :id (keyword (name id) "interceptor")
      :before (fn [context]
                (let [{:keys [db event]} (get context :coeffects)
                      [ev & args] event
                      current-state (get-in db path)
                      new-state (if (= ev (keyword (name id) "set-initial-state"))
                                  initial-state
                                  (get-in state-machine [:transitions current-state ev]))
                      new-db (assoc-in db path new-state)]
                  (assert (not (nil? new-state)) (let [expected (transitions state-machine current-state)]
                                                   (str "No transition found for event " ev " in state " current-state
                                                        (cond
                                                          (= (count expected) 1) (str "\nExpected:\n" (first expected))
                                                          (= (count expected) 0) (str "\nExpected one of:\n" (string/join "\n" expected))))))
                  (when (contains? modifiers :locket/debug-transition)
                    (println (str id " " current-state "\n------\n " ev " -> " new-state "\n")))
                  (assoc-in context [:effects :db] new-db))))))

(defn install
  "Installs a state-machine.
   This is a side-effecting operation that adds handlers."
  [state-machine]
  (let [{:keys [id path initial-state]} state-machine
        evs (conj (transitions state-machine) (keyword (name id) "set-initial-state"))
        interceptor (interceptor state-machine)]
    (doseq [ev evs
            :let [old-interceptor (registrar/get-handler :event ev)]]
      (if old-interceptor
        (do (re-frame/clear-event ev)
            (events/register ev [old-interceptor interceptor]))
        (events/register ev [cofx/inject-db fx/do-fx interceptor])))
    (re-frame/dispatch [(keyword (name id) "set-initial-state")])))

(re-frame/reg-fx :locket/install-state-machine install)
