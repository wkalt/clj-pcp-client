;; Clojure script for a trivial agent, to be executed with 'lein exec -p'

(ns example-agent
    (:require [clojure.tools.logging :as log]
      [puppetlabs.pcp.client :as client]
      [puppetlabs.pcp.message :as message]))

(defn pcp-error-handler
      [conn msg]
      (log/warn "^^^ PCP error handler got message" msg
                 "\n  Description: " (:description (message/get-json-data msg))))

(defn request-handler
      [conn request]
      (log/info "&&& request handler got message" request)
      (let [request-data (message/get-json-data request)
            requester (:sender request)]
           (if-let [action (:action request-data)]
                   (if (= action "demo")
                     (do
                       (log/info "### sending back DEMO response")
                       (client/send!
                         conn
                         (-> (message/make-message)
                             (message/set-expiry 4 :seconds)
                             (assoc :targets [requester]
                                    :message_type "example/response")
                             (message/set-json-data {:demo "Hey, here's my demo!"}))))
                     (do
                       (log/info "### sending back DEFAULT response")
                       (client/send!
                         conn
                         (-> (message/make-message)
                             (message/set-expiry 4 :seconds)
                             (assoc :targets [requester]
                                    :message_type "example/response")
                             (message/set-json-data {:default "I don't know this action..."})))))
                   (do
                     (log/info "### sending back ERROR message")
                     (client/send!
                       conn
                       (-> (message/make-message)
                           (message/set-expiry 4 :seconds)
                           (assoc :targets [requester]
                                  :message_type "example/error")
                           (message/set-json-data {:error "I need some action :("})))))))

(defn default-msg-handler
      [conn msg]
      (log/info "Default handler got message" msg))

(def agent-params
  {:server      "wss://localhost:8142/pcp/"
   :cert        "test-resources/ssl/certs/client02.example.com.pem"
   :private-key "test-resources/ssl/private_keys/client02.example.com.pem"
   :cacert      "test-resources/ssl/certs/ca.pem"
   :type        "agent"})

(def agent-handlers
  {"http://puppetlabs.com/error_message" pcp-error-handler
   "example/request" request-handler
   :default default-msg-handler})

(defn start
  "Connect to the broker and wait for requests"
  []
  (log/info "### connecting")
   ;; NB: with-open will ensure that the agent is properly closed
  (with-open [agent (client/connect agent-params agent-handlers)]
       (client/wait-for-connection agent (* 60 1000))
       (client/start-heartbeat-thread agent)
       (client/wait-for-association agent (* 60 1000))
       (log/info "### connected")
       (while (client/connected? agent)
              (Thread/sleep 1000))
       (log/info "### connection dropped - terminating")))

(start)
