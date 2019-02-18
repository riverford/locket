(ns locket.core
  (:require [clojure.string :as string]
            [re-frame.core :as re-frame]
            [re-frame.loggers :as loggers]
            [re-frame.registrar :as registrar]
            [re-frame.events :as events]
            [re-frame.cofx :as cofx]
            [re-frame.fx :as fx]
            [re-frame.db :as db]))

(defonce registry
  (atom {}))

(defn all-transitions
  "Returns all the unique transitions (events) that a state machine handles"
  [state-machine]
  (let [{:keys [transitions]} state-machine]
    (into #{} (comp (map second) cat (map first)) transitions)))

(defn state
  "Returns the current state of a state machine, given the id, a re-frame db and an optional query-v"
  ([db id]
   (state db id nil))
  ([db id query-v]
   (let [{:keys [path-fn initial-state]} (get-in @registry [id :state-machine])
         path (path-fn query-v)]
     (get-in db path initial-state))))

(defn transitions
  "Returns the transitions from the current state of a state machine"
  ([db id]
   (transitions db id nil))
  ([db id query-v]
   (let [{:keys [transitions]} (get-in @registry [id :state-machine])]
     (into #{} (comp (map second) cat (map first)) transitions))))

(defn reset-state
  "Returns a db with the state machine reset to its initial state"
  ([db id]
   (reset-state db id nil))
  ([db id query-v]
   (let [{:keys [path-fn initial-state]} (get-in @registry [id :state-machine])
         path (path-fn query-v)]
     (assoc-in db path initial-state))))

(defn- interceptor
  "Generic interceptor for state machine interactions.

   * Updates the state of the state machine based on the reframe event
   * Removes any events when the transition is not found in the state machine.
   * `path-fn` is called on the event-v of the event to find the path to update
   * The `debug-fn` will be called with the transition data if debug? is true"
  [state-machine]
  (let [{:keys [id initial-state path-fn debug? debug-fn]} state-machine]
    (re-frame/->interceptor
      :id id
      :before (fn [context]
                (let [{db :db, event-v :event} (get context :coeffects)
                      [event & args] event-v
                      path (path-fn event-v)
                      current-state (get-in db path initial-state)
                      new-state (get-in state-machine [:transitions current-state event])]
                  (when debug?
                    (debug-fn {:path path
                               :current-state current-state
                               :event event
                               :new-state new-state}))
                  ;; stop the event from happening at all if there is no transition
                  (if (nil? new-state)
                    (assoc context :queue nil)
                    (assoc-in context [:coeffects :db] (assoc-in db path (or new-state current-state))))))
      :after (fn [context]
               ;; ensure that the db coeffect becomes an effect if it is present
               (if (and (get-in context [:coeffects :db])
                        (not (get-in context [:effects :db])))
                 (assoc-in context [:effects :db] (get-in context [:coeffects :db]))
                 context)))))

(defn- add-state-machine!
  "Adds the state machine handling to the re-frame registry for the given state machine.

  Detects existing event handlers and interceptor chains, and replaces them with
  a new interceptor chain that includes the locket interceptor.

  Creates event handlers if they do not exist.

  Leaves metadata on the interceptor change to indicate that we've been in here and messed around"
  [state-machine]
  (let [{:keys [id]} state-machine
        events (all-transitions state-machine)
        interceptor (interceptor state-machine)]
    (swap! registry (fn [registry]
                      (-> (reduce
                            (fn [registry event]
                              (let [interceptors (registrar/get-handler :event event)]
                                (if interceptors
                                  ;; insert the state machine interceptor before the core handler
                                  (let [new-interceptors (concat (butlast interceptors)
                                                                 [interceptor (last interceptors)])]
                                    (re-frame/clear-event event)
                                    (events/register event new-interceptors)
                                    (update-in registry [id :altered-events] (fnil conj #{}) event))

                                  (do
                                    (events/register event [cofx/inject-db fx/do-fx interceptor])
                                    (update-in registry [id :generated-events] (fnil conj #{}) event)))))
                            registry
                            events)
                          (assoc-in [id :state-machine] state-machine))))))

(defn- remove-state-machine!
  "Removes the state machine handling for the given state machine,
   removing any generated events and the taking the state machine interceptor out of the
   interceptor chains."
  [id]
  (when-let [{:keys [state-machine altered-events generated-events]} (get @registry id)]
    (let [remove-generated-events (fn [registry]
                                    (reduce
                                      (fn [registry event]
                                        (re-frame/clear-event event)
                                        (update-in registry [id :generated-events] disj event))
                                      registry
                                      generated-events))
          revert-altered-events (fn [registry]
                                  (reduce
                                    (fn [registry event]
                                      (let [interceptors (registrar/get-handler :event event)]
                                        (re-frame/clear-event event)
                                        (events/register event (filter #(not= (:id %) id) interceptors))
                                        (update-in registry [id :altered-events] disj event)))
                                    registry
                                    altered-events))]
      (swap! registry (fn [registry]
                        (-> (revert-altered-events registry)
                            (remove-generated-events)
                            (dissoc id)))))))

(defn transition->str
  "Takes fired transition data and turns it into a neat string representation"
  [transition]
  (let [{:keys [path current-state event new-state]} transition]
    (str path " • [" (or current-state "<nil>") ", " (or event "<nil>") "] "
         (if (nil? new-state)
           "-x No transition found"
           (str "-> " new-state)))))

(defn- patch-state-machine
  "Patch up the state machine Ensure the state-machine has a path-fn, by constructing it from the id if not provided."
  [state-machine]
  (let [{:keys [id path-fn debug-fn]} state-machine]
    (cond-> state-machine
      (nil? path-fn) (assoc :path-fn (constantly [:locket/state id]))
      (nil? debug-fn) (assoc :debug-fn (fn [transition] (loggers/console :log (transition->str transition)))))))

(defn install-state-machine!
  "Installs a locket state machine. This function is side-effecting and alters the re-frame registry.
   It should be called after the definitions of the related event handlers."
  [state-machine]
  (let [{:keys [id]} state-machine]
    (remove-state-machine! id)
    (add-state-machine! (patch-state-machine state-machine))))
