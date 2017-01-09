;; Clojure script for a trivial controller, to be executed with 'lein exec -p'

(ns example-controller
    (:require
      [clojure.tools.logging :as log]
      [puppetlabs.pcp.client :as client]
      [puppetlabs.pcp.message-v2 :as message]))

(defn associate-session-handler
  [conn msg]
  (log/info "^^^ PCP associate session handler got message" msg))

(defn pcp-error-handler
  [conn msg]
  (log/info "^^^ PCP error handler got message" msg
             "\n  Description: " (:description (message/get-data msg))))

(defn inventory-handler
  [conn msg]
  (log/info "^^^ PCP inventory handler got message" msg
             "\n  URIs: " (:uris (message/get-data msg))))

(defn response-handler
  [conn msg]
      (log/info "&&& response handler got message" msg
                 "\n  &&& &&& RESPONSE:" (message/get-data msg)))

(defn agent-error-handler
  [conn msg]
      (log/warn "&&& error handler got message" msg))

(defn default-msg-handler
  [conn msg]
  (log/warn "&&& Default handler got message" msg))

(def controller-params
  {:server      "wss://localhost:8142/pcp/"
   :cert        "test-resources/ssl/certs/client01.example.com.pem"
   :private-key "test-resources/ssl/private_keys/client01.example.com.pem"
   :cacert      "test-resources/ssl/certs/ca.pem"
   :type        "controller"})

(def controller-handlers
  {"http://puppetlabs.com/associate_response" associate-session-handler
   "http://puppetlabs.com/inventory_response" inventory-handler
   "http://puppetlabs.com/error_message" pcp-error-handler
   "example/response" response-handler
   "example/error" agent-error-handler
   :default default-msg-handler})

(defn start
  "Connect to the broker and send a request to the agent"
  []
  (log/info "### controller: connecting")
  (with-open [cl (client/connect controller-params controller-handlers)]
       (client/wait-for-connection cl (* 10 1000))
       (log/info "### controller: starting WebSocket heartbeat thread")
       (client/start-heartbeat-thread cl)
       (log/info "### controller: waiting for the WebSocket session being associated")
       (log/info "### controller: sending inventory request")
       (client/send!
         cl
         (-> (message/make-message)
             (assoc :target "pcp:///server"
                    :message_type "http://puppetlabs.com/inventory_request")
             (message/set-data {:query ["pcp://*/agent"]})))

       (log/info "### controller: sending agent request")
       (client/send!
         cl
         (-> (message/make-message)
             (assoc :target "pcp://*/agent"
                    :message_type "example/request")
             (message/set-data {:action "demo"})))
       (log/info "### controller: waiting for 60 s")
       (Thread/sleep 60000)))

(time (start))
