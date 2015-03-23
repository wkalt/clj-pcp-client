# Sample usage

```clojure
(ns example-client (:require [puppetlabs.cthun.client :as client]
                             [puppetlabs.cthun.message :as message]))

;; handlers take a connection and message parameter
(defn cnc-request-handler
  [conn request]
  (let [response (-> (message/reply request)
                     (assoc :schema "example/cnc_response")
                     (message/set-json-data {:response "Hello world"}))]
    (client/send! conn response)))

(defn default-request-handler
  [conn request]
  (log/info "Got message" request))

;; connecting with handlers
(def conn (client/connect
           {:server "wss://localhost:8090/cthun/"
            :key "/var/lib/puppet/ssl/private_keys/client.pem"
            :cert "/var/lib/puppet/ssl/certs/client.pem"
            :ca "/var/lib/puppet/ssl/certs/ca.pem"
            :identity "cth://client/demo-client"}
           (handle "example/cnc_request" cnc-request-handler)
           (default default-request-handler)))

```

## Less macro-y version of the connect


```clojure
(def conn (client/connect
           {:server "wss://localhost:8090/cthun/"
            :key "/var/lib/puppet/ssl/private_keys/client.pem"
            :cert "/var/lib/puppet/ssl/certs/client.pem"
            :ca "/var/lib/puppet/ssl/certs/ca.pem"
            :identity "cth://client/demo-client"}
           {"example/cnc_request" cnc-request-handler
            :default default-request-handler}))
```
