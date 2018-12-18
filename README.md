![Logo](/locket3.png)

# Locket

`[riverford/locket "2018.12.18-07"]`

A pocket-sized state machine library for re-frame. 

## Rationale

If you've followed the example along at http://blog.cognitect.com/blog/2017/8/14/restate-your-ui-creating-a-user-interface-with-re-frame-and-state-machines, you might find yourself wanting a library to reduce the accompanying boiler plate a little. 

This does exactly that, by providing a re-frame effect handler that registers events for all the state machine transitions, so you don't have to. 

This not only reduces boiler plate, but also eliminates the risk of your state getting out of sync (if you forget to call `update-state`). 

## Usage

``` clojure
(ns example.events
  (:require
   [re-frame.core :as re-frame]
   [example.db :as db]))
   
(def state-machine
  {:id :auth
   :path [:auth/state]
   :initial-state :ready
   :transitions {:ready {:auth/login :logging-in}
                 :logging-in {:auth.login/success :logged-in
                              :auth.login/failure :error}
                 :logged-in {:auth/logout :logging-out}
                 :logging-out {:auth.logout/success :ready}
                 :error {:auth/login :logging-in}}})

(re-frame/reg-event-fx
 ::init
 (fn [cofx _]
   (let [{:keys [db]} cofx]
     {:db db/default-db
      :locket/install-state-machine state-machine})))

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
```

## License

Copyright © 2018 Riverford

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
