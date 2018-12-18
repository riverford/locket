(ns locket.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.cofx :as cofx]
            [re-frame.fx :as fx]))

(defn ns-kw
  "Namespace keyword k2 with keyword k1"
  [k1 k2]
  (keyword (name k1) (name k2)))

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
  (let [{:keys [id path initial-state]} state-machine]
    (re-frame/->interceptor
      :id (ns-kw id :interceptor)
      :before (fn [context]
                (let [{:keys [db event]} (get context :coeffects)
                      [ev & args] event
                      current-state (get-in db path)
                      new-state (if (= ev (ns-kw id :init))
                                  initial-state
                                  (get-in state-machine [:transitions current-state ev]))
                      new-db (assoc-in db path new-state)]
                  (assert (not (nil? new-state)) (let [expected (transitions state-machine current-state)]
                                                   (str "No transition found for event " ev " in state " current-state
                                                        (cond
                                                          (= (count expected) 1) (str "\nExpected:\n" (first expected))
                                                          (= (count expected) 0) (str "\nExpected one of:\n" (string/join "\n" expected))))))
                  (assoc-in context [:effects :db] new-db))))))

(defn install
  "Installs a state-machine.
   This is a side-effecting operation that adds handlers."
  [state-machine]
  (let [{:keys [id path]} state-machine
        evs (conj (transitions state-machine) (ns-kw id :init))
        interceptor (interceptor state-machine)]
    (doseq [ev evs
            :let [old-interceptor (registrar/get-handler :event ev)]]
      (if old-interceptor
        (do (re-frame/clear-event ev)
            (events/register ev [old-interceptor interceptor]))
        (events/register ev [cofx/inject-db fx/do-fx interceptor])))
    (re-frame/reg-sub
      (ns-kw id :state)
      (fn [db] (get-in db path)))
    (re-frame/reg-sub
      (ns-kw id :transitions)
      :<- [(ns-kw id :state)]
      (fn [state]
        (transitions state-machine state)))
    (re-frame/dispatch [(ns-kw id :init)])))

(re-frame/reg-fx :locket/install-state-machine install)
