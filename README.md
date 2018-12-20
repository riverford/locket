![Logo](/locket5.png)

# Locket

`[riverford/locket "2018.12.19-05"]`

A pocket-sized state machine library for re-frame. 

## Rationale

If you've followed the example along at http://blog.cognitect.com/blog/2017/8/14/restate-your-ui-creating-a-user-interface-with-re-frame-and-state-machines, you might find yourself wanting a library to reduce the accompanying boiler-plate a little. 

This does exactly that, by providing a re-frame effect handler that registers events for all the state machine transitions, so you don't have to. 

This not only reduces boiler-plate, but also eliminates the risk of your state getting out of sync (i.e. if you forget to call `update-next-state`). 

## Usage

![states](/states2.gif)

``` clojure
(ns example.core
  (:require
   [locket.core :as locket]
   [re-frame.core :as re-frame]
   [example.db :as db]))
   
(def state-machine
  {;; required - the path in the db to store the current state
   :path [:auth/state]

   ;; required - the state machine transitions - a map of state -> event -> new-state
   :transitions {:ready {:auth/login :logging-in}
                 :logging-in {:auth.login/success :logged-in
                              :auth.login/failure :error}
                 :logged-in {:auth/logout :logging-out}
                 :logging-out {:auth.logout/success :ready}
                 :error {:auth/login :logging-in}}

   ;; optional - the initial state for the state machine
   :initial-state :ready 
   
   ;; optional - set to true to log all transitions
   :debug? false 
   
   ;; optional - set behaviours for when events fire which do not have valid transitions from the current state
   ;;  :warn - prints a warning message to the console 
   ;;  :prevent - prevent the reframe event from firing
   :on-invalid-transition #{:warn :prevent}})

;; Installing the state machine (via `locket/install-state-machine`) 
;; sets up handlers and subscriptions. 

(re-frame/reg-event-fx
 ::init
 (fn [cofx _]
   (let [{:keys [db]} cofx]
     {:db db/default-db
      :dispatch [:locket/install-state-machine state-machine]})))

(re-frame/reg-event-fx
  :auth/login
  (fn [cofx]
    {:dispatch-later [{:ms 3000
                       :dispatch [:auth.login/success]}]}))

(re-frame/reg-event-fx
  :auth/logout
  (fn [cofx]
    {:dispatch-later [{:ms 3000
                       :dispatch [:auth.logout/success]}]}))

;; Subscriptions for the current state
(re-frame/reg-sub 
  :auth/state
  (fn [db]
    (get db :auth/state)))
    
(re-frame/reg-sub 
  :auth/transitions
  :<- [:auth/state]
  (fn [state]
    (locket/transitions state-machine state)))

;; A view showing the current state and available transitions
(defn main-panel []
  (let [state (re-frame/subscribe [:auth/state])
        transitions (re-frame/subscribe [:auth/transitions])]
    [:div {:class "pa5"}
     [:p {:class "f3"} "Current state"]
     [:div {:class "flex flex-row items-center"}
      [:p {:class "f5 mr3"} @state]
      (when (contains? #{:logging-in :logging-out} @state)
        [:img {:src "https://cdnjs.cloudflare.com/ajax/libs/galleriffic/2.0.1/css/loader.gif"
               :style {:width 20
                       :height 20}}])]
     [:p {:class "f3"} "Transitions"]
     (for [t @transitions]
       [:p {:key (str t)
            :class "f5"
            :style {:cursor "pointer"}
            :on-click (fn [_]
                        (re-frame/dispatch [t]))}
        (name t)])]))
```

## License

Copyright © 2018 Riverford

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
