# Locket

A pocket-sized state machine library for re-frame. 

![Logo](/locket.png)

## Rationale

If you've followed the example along at http://blog.cognitect.com/blog/2017/8/14/restate-your-ui-creating-a-user-interface-with-re-frame-and-state-machines, you might find yourself wanting a library to reduce the accompanying boiler plate a little. 

This does exactly that, by providing a re-frame effect handler that registers events for all the state machine transitions, so you don't have to. 

This not only reduces boiler plate, but also eliminates the risk of your state getting out of sync (if you forget to call `update-state`). 

## Usage

```
(ns example.events
  (:require
   [re-frame.core :as re-frame]
   [example.db :as db]))
   
(def state-machine
  {:id :login
   :path [:login/state]
   :initial-state :ready
   :transitions {:ready {:login/login :logging-in}
                 :logging-in {:login.login/success :logged-in
                              :login.login/failure :error}
                 :logged-in {:login/logout :logging-out}
                 :logging-out {:login.logout/success :ready
                               :login.logout/failure :error}
                 :error {:login/login :logging-in}}})

(re-frame/reg-event-fx
 ::init
 (fn [cofx _]
   (let [{:keys [db]} cofx]
     {:db db/default-db
      :locket/install-state-machine state-machine})))

(re-frame/reg-event-fx
  :login/login
  (fn [cofx]
    {:dispatch-later [{:ms 3000
                       :dispatch [:login.login/success]}]}))

(re-frame/reg-event-fx
  :login/logout
  (fn [cofx]
    {:dispatch-later [{:ms 3000
                       :dispatch [:login.logout/success]}]}))```

## License

Copyright © 2018 Riverford

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
