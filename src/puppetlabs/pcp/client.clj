(ns puppetlabs.pcp.client
  (:require [clojure.tools.logging :as log]
            [gniazdo.core :as ws]
            [puppetlabs.pcp.message :as message :refer [Message]]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [schema.core :as s])
  (:use [slingshot.slingshot :only [throw+ try+]])
  (:import  (clojure.lang Atom)
            (java.nio ByteBuffer)
            (org.eclipse.jetty.websocket.client WebSocketClient)
            (org.eclipse.jetty.util.ssl SslContextFactory)))

(defprotocol ClientInterface
  "client interface - make one with connect"
  (connecting? [client]
    "Returns true if the client is currently connecting to the pcp-broker")
  (connected? [client]
    "Returns true if the client is currently connected to the pcp-broker")
  (associating? [client]
    "Returns true if the client has not yet recieved an association response")
  (associated? [client]
    "Returns true if the client has been successfully assocated with a broker")
  (wait-for-connection [client timeout-ms]
    "Wait up to timeout-ms for a connection to be established.
    Returns the client if the connection has been established, else nil")
  (wait-for-association [client timeout-ms]
    "Wait up to timeout-ms for a connection to be associated.  Returns the client if
     the connection has been associated, else nil.

     NOTE: There are two ways assocation may fail, we may not have
     recieved an association response in the timeout specified, or the
     association request may have been denied.  Check associating? and
     associated? if you are interested in detecting the difference.")
  (send! [client message]
    "Send a message across the currently connected client.  Will
    raise ::not-connected if the client is not currently connected to
    a broker.")
  (close [client]
    "Close the connection.  Once the client is close you will need a
    new one."))

(def Handlers
  "schema for handler map.  String keys are data_schema handlers,
  keyword keys are special handlers (like :default)"
  {(s/either s/Str s/Keyword) (s/pred fn?)})

;; forward declare implementations of protocol functions.  We prefix
;; with the dash so they're not clashing with the versions defined by
;; ClientInterface
(declare -connecting? -connected?
         -associating? -associated?
         -wait-for-connection -wait-for-association -send! -close)

(s/defrecord Client
  [server :- s/Str
   identity :- p/Uri
   handlers :- Handlers
   should-stop ;; promise that when delivered means should stop
   websocket-connection ;; atom of a promise that will be a connection or true
   websocket-client
   associate-response ;; atom of a promise that will be a boolean
   ]
  ClientInterface
  (connecting? [client] (-connecting? client))
  (connected? [client] (-connected? client))
  (associating? [client] (-associating? client))
  (associated? [client] (-associated? client))
  (wait-for-connection [client timeout] (-wait-for-connection client timeout))
  (wait-for-association [client timeout] (-wait-for-association client timeout))
  (send! [client message] (-send! client message))
  (close [client] (-close client)))

(s/defn ^:always-validate ^:private -connecting? :- s/Bool
  [client :- Client]
  (let [{:keys [websocket-connection]} client]
    (not (realized? @websocket-connection))))

(s/defn ^:always-validate ^:private -connected? :- s/Bool
  [client :- Client]
  (let [{:keys [websocket-connection]} client]
    (and (realized? @websocket-connection) (not (= @@websocket-connection true)))))

(s/defn ^:always-validate ^:private -associating? :- s/Bool
  [client :- Client]
  (let [{:keys [associate-response]} client]
    (not (realized? @associate-response))))

(s/defn ^:always-validate ^:private -associated? :- s/Bool
  [client :- Client]
  (let [{:keys [associate-response]} client]
    (and (realized? @associate-response) @@associate-response)))

(s/defn ^:always-validate ^:private session-association-message :- Message
  [client :- Client]
  (let [{:keys [identity]} client]
    (-> (message/make-message :message_type "http://puppetlabs.com/associate_request"
                              :sender identity
                              :targets ["pcp:///server"])
        (message/set-expiry 3 :seconds))))

(s/defn ^:always-validate ^:private associate-response-handler
  [client :- Client message :- Message]
  (let [data (message/get-json-data message)
        {:keys [success]} data
        {:keys [associate-response]} client]
    (s/validate p/AssociateResponse data)
    (log/debug "Received associate_response message" message data)
    (deliver @associate-response success)))

(s/defn ^:always-validate ^:private fallback-handler
  "The handler to use when no handler matches"
  [client :- Client message :- Message]
  (log/debug "no handler for " message))

(s/defn ^:always-validate ^:private dispatch-message
  [client :- Client message :- Message]
  (let [message-type (:message_type message)
        handlers (:handlers client)
        handler (or (get handlers message-type)
                    (get handlers :default)
                    fallback-handler)]
    (handler client message)))

(s/defn ^:always-validate ^:private make-identity :- p/Uri
  "extracts the common name from the named certificate and forms a PCP
  Uri with it and the supplied type"
  [certificate type]
  (let [x509     (ssl-utils/pem->cert certificate)
        cn       (ssl-utils/get-cn-from-x509-certificate x509)
        identity (format "pcp://%s/%s" cn type)]
    identity))

(s/defn ^:always-validate ^:private heartbeat
  "Starts the WebSocket heartbeat task that sends pings over the
  current set of connections as long as the 'should-stop' promise has
  not been delivered.  Will keep a connection alive, or detect a
  stalled connection earlier."
  [client :- Client]
  (log/debug "WebSocket heartbeat task is about to start" client)
  (let [{:keys [should-stop websocket-client]} client]
    (while (not (deref should-stop 15000 false))
      (let [sessions (.getOpenSessions websocket-client)]
        (log/debug "Sending WebSocket ping")
        (doseq [session sessions]
          (.. session (getRemote) (sendPing (ByteBuffer/allocate 1))))))
    (log/debug "WebSocket heartbeat task is about to finish")))

(s/defn ^:always-validate ^:private make-connection :- Object
  "Returns a connected websocket connection"
  [client :- Client]
  (let [{:keys [server websocket-client associate-response should-stop]} client
        initial-sleep 200
        sleep-multiplier 2
        maximum-sleep (* 15 1000)]
    (loop [retry-sleep initial-sleep]
      (or (try+
            (log/debugf "Making connection to %s" server)
            (ws/connect server
                        :client websocket-client
                        :on-connect (fn [session]
                                      (log/debug "WebSocket connected")
                                      (let [message (session-association-message client)
                                            buffer  (ByteBuffer/wrap (message/encode message))]
                                        (.. session (getRemote) (sendBytes buffer)))
                                      (log/debug "sent associate session request"))
                        :on-error (fn [error]
                                    (log/error error "WebSocket error"))
                        :on-close (fn [code message]
                                    (log/debug "WebSocket closed" code message)
                                    (reset! associate-response (promise))
                                    (let [{:keys [should-stop websocket-connection]} client]
                                      (when (not (realized? should-stop))
                                        (reset! websocket-connection (future (make-connection client))))))
                        :on-receive (fn [text]
                                      (log/debug "received text message")
                                      (dispatch-message client (message/decode (message/string->bytes text))))
                        :on-binary (fn [buffer offset count]
                                     (let [message (message/decode buffer)]
                                       (log/debug "received bin message - offset/bytes:" offset count
                                                  "- message:" message)
                                       (dispatch-message client message))))
            (catch java.net.ConnectException exception
              (log/debugf exception "Didn't get connected.  Sleeping for up to %dms to retry" retry-sleep)
              (deref should-stop retry-sleep nil))
            (catch Object _
              (log/error (:throwable &throw-context) "unexpected error")
              (throw+)))
          (recur (min maximum-sleep (* retry-sleep sleep-multiplier)))))))


(s/defn ^:always-validate -wait-for-connection :- (s/maybe Client)
  "Waits until a client is connected.  If timeout is hit, returns falsey"
  [client :- Client timeout :- s/Num]
  (let [{:keys [websocket-connection]} client]
    (if (deref @websocket-connection timeout nil)
      client
      nil)))

(s/defn ^:always-validate -wait-for-association :- (s/maybe Client)
  "Waits until a client is associated.  If timeout is hit, or the association doesn't work, returns falsey"
  [client :- Client timeout :- s/Num]
  (let [{:keys [associate-response]} client]
    (if (deref @associate-response timeout nil)
      client
      nil)))

(s/defn ^:always-validate ^:private -send! :- s/Bool
  "Send a message across the websocket session"
  [client :- Client message :- message/Message]
  (let [{:keys [identity websocket-connection]} client]
    (if-not (-associated? client)
      (throw+ {:type ::not-associated})
      (ws/send-msg @@websocket-connection
                   (message/encode (assoc message :sender identity))))
    true))

(s/defn ^:always-validate -close :- s/Bool
  "Close the connection"
  [client :- Client]
  (log/debug "Closing")
  (let [{:keys [should-stop websocket-client websocket-connection]} client]
    ;; NOTE:  This true value is also the sentinel for make-connection
    (deliver should-stop true)
    (if (-connected? client)
      (ws/close @@websocket-connection))
    (.stop websocket-client))
  true)

(def ConnectParams
  "schema for connection parameters"
  {:server s/Str
   :cacert s/Str
   :cert s/Str
   :private-key s/Str
   :type s/Str})

;; private helpers for the ssl/websockets setup
(s/defn ^:always-validate ^:private make-ssl-context :- SslContextFactory
  "Returns an SslContextFactory that does client authentication based on the
  client certificate named"
  [params]
  (let [factory (SslContextFactory.)
        {:keys [cert private-key cacert]} params
        ssl-context (ssl-utils/pems->ssl-context cert private-key cacert)]
    (.setSslContext factory ssl-context)
    (.setNeedClientAuth factory true)
    factory))

(s/defn ^:always-validate ^:private make-websocket-client :- WebSocketClient
  "Returns a WebSocketClient with the correct SSL context"
  [params :- ConnectParams]
  (let [client (WebSocketClient. (make-ssl-context params))]
    (.start client)
    client))

(s/defn ^:always-validate connect :- Client
  "Asyncronously establishes a connection to a pcp-broker named by
  `:server`.  Returns a Client"
  [params :- ConnectParams handlers :- Handlers]
  (let [{:keys [cert type server]} params
        client (map->Client {:server server
                             :identity (make-identity cert type)
                             :websocket-client (make-websocket-client params)
                             :websocket-connection (atom (future true))
                             :associate-response (atom (promise))
                             :handlers (assoc handlers
                                              "http://puppetlabs.com/associate_response" associate-response-handler)
                             :should-stop (promise)})
        {:keys [websocket-connection]} client]
    (.start (Thread. (partial heartbeat client)))
    (reset! websocket-connection (future (make-connection client)))
    client))
