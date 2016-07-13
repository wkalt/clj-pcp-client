(ns puppetlabs.pcp.client
  (:require [clojure.tools.logging :as log]
            [gniazdo.core :as ws]
            [puppetlabs.pcp.message :as message :refer [Message]]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [schema.core :as s]
            [puppetlabs.i18n.core :as i18n])
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
    "Returns true if the client is currently connected to the pcp-broker.
    Propagates any unhandled exception thrown while attempting to connect.")
  (associating? [client]
    "Returns true if the client has not yet recieved an association response")
  (associated? [client]
    "Returns true if the client has been successfully assocated with a broker")
  (wait-for-connection [client timeout-ms]
    "Wait up to timeout-ms for a connection to be established.
    Returns the client if the connection has been established, else nil.
    Propagates any unhandled exception thrown while attempting to connect.")
  (wait-for-association [client timeout-ms]
    "Wait up to timeout-ms for a connection to be associated.  Returns the client if
    the connection has been associated, else nil.

     NOTE: There are two ways assocation may fail, we may not have
     recieved an association response in the timeout specified, or the
     association request may have been denied.  Check associating? and
     associated? if you are interested in detecting the difference.")
  (send! [client message]
    "Send a message across the currently connected client.  Will
    raise ::not-connected if the client is not currently associated with the
    pcp-broker.")
  (close [client]
    "Close the connection.  Once the client is close you will need a
    new one.

     NOTE: you must invoke this function to properly close the connection,
     otherwise reconnection attempts may happen and the heartbeat thread will
     persist, in case it was previously started.")
  (start-heartbeat-thread [client]
    "Start a thread that will periodically send WebSocket pings to the pcp-broker.
    The heartbeat thread will be stopped once the 'close' is invoked.
    Propagate any unhandled exception thrown while attempting to connect.
    Will raise ::not-connected if the client is not currently connected with the
    pcp-broker."))

(def Handlers
  "schema for handler map.  String keys are data_schema handlers,
  keyword keys are special handlers (like :default)"
  {(s/either s/Str s/Keyword) (s/pred fn?)})

;; forward declare implementations of protocol functions.  We prefix
;; with the dash so they're not clashing with the versions defined by
;; ClientInterface
(declare -connecting? -connected?
         -associating? -associated?
         -wait-for-connection -wait-for-association -send! -close
         -start-heartbeat-thread)

(s/defrecord Client
  [server :- s/Str
   identity :- p/Uri
   handlers :- Handlers
   should-stop ;; promise that when delivered means should stop
   websocket-connection ;; atom of a promise that will be a connection or true
   websocket-client
   associate-response ;; atom of a promise that will be a boolean
   ]
  {(s/optional-key :user-data) s/Any} ;; a field for user data
  ClientInterface
  (connecting? [client] (-connecting? client))
  (connected? [client] (-connected? client))
  (associating? [client] (-associating? client))
  (associated? [client] (-associated? client))
  (wait-for-connection [client timeout] (-wait-for-connection client timeout))
  (wait-for-association [client timeout] (-wait-for-association client timeout))
  (send! [client message] (-send! client message))
  (close [client] (-close client))
  (start-heartbeat-thread [client] (-start-heartbeat-thread client)))

(s/defn ^:private -connecting? :- s/Bool
  [client :- Client]
  (let [{:keys [websocket-connection]} client]
    (not (realized? @websocket-connection))))

(s/defn ^:private -connected? :- s/Bool
  [client :- Client]
  (let [{:keys [websocket-connection]} client]
    (and (realized? @websocket-connection) (not (= @@websocket-connection true)))))

(s/defn ^:private -associating? :- s/Bool
  [client :- Client]
  (let [{:keys [associate-response]} client]
    (not (realized? @associate-response))))

(s/defn ^:private -associated? :- s/Bool
  [client :- Client]
  (let [{:keys [associate-response]} client]
    (and (realized? @associate-response) @@associate-response)))

(s/defn ^:private session-association-message :- Message
  [client :- Client]
  (let [{:keys [identity]} client]
    (-> (message/make-message :message_type "http://puppetlabs.com/associate_request"
                              :sender identity
                              :targets ["pcp:///server"])
        (message/set-expiry 3 :seconds))))

(s/defn ^:private associate-response-handler
  [client :- Client message :- Message]
  (let [data (message/get-json-data message)
        {:keys [success]} data
        {:keys [associate-response]} client]
    (s/validate p/AssociateResponse data)
    (log/debug (i18n/trs "Received associate_response message {0} {1}" message data))
    (deliver @associate-response success)))

(s/defn ^:private fallback-handler
  "The handler to use when no handler matches"
  [client :- Client message :- Message]
  (log/debug (i18n/trs "No handler for {0}" message)))

(s/defn ^:private dispatch-message
  [client :- Client message :- Message]
  (let [message-type (:message_type message)
        handlers (:handlers client)
        handler (or (get handlers message-type)
                    (get handlers :default)
                    fallback-handler)]
    (handler client message)))

(s/defn ^:private make-identity :- p/Uri
  "extracts the common name from the named certificate and forms a PCP
  Uri with it and the supplied type"
  [certificate type]
  (let [x509     (ssl-utils/pem->cert certificate)
        cn       (ssl-utils/get-cn-from-x509-certificate x509)
        identity (format "pcp://%s/%s" cn type)]
    identity))

(s/defn ^:private heartbeat
  "Provides the WebSocket heartbeat task that sends pings over the
  current set of connections as long as the 'should-stop' promise has
  not been delivered.  Will keep a connection alive, or detect a
  stalled connection earlier."
  [client :- Client]
  (let [{:keys [should-stop websocket-client]} client]
    (while (not (deref should-stop 15000 false))
      (let [sessions (.getOpenSessions websocket-client)]
        (log/debug (i18n/trs "Sending WebSocket ping"))
        (doseq [session sessions]
          (.. session (getRemote) (sendPing (ByteBuffer/allocate 1))))))
    (log/debug (i18n/trs "WebSocket heartbeat task is about to finish"))))

(s/defn ^:private -start-heartbeat-thread
  "Ensures that the WebSocket connection is established and starts the WebSocket
  heartbeat task.  Propagates any unhandled exception thrown while attempting
  to connect. Rasises ::not-connected in case the connection was not established."
  [client :- Client]
  (log/trace (i18n/trs "Ensuring that the WebSocket is connected"))
  (when-not (-connected? client)
    (throw+ {:type ::not-connected}))
  (log/debug (i18n/trs "WebSocket heartbeat task is about to start {0}" client))
  (.start (Thread. (partial heartbeat client))))


(s/defn ^:private make-connection :- Object
  "Returns a connected WebSocket connection.  In case of a SSLHandShakeException
  or ConnectException a further connection attempt will be made by following an
  exponential backoff, whereas other exceptions will be propagated."
  [client :- Client]
  (let [{:keys [server websocket-client associate-response should-stop]} client
        initial-sleep 200
        sleep-multiplier 2
        maximum-sleep (* 15 1000)]
    (loop [retry-sleep initial-sleep]
      (or (try+
            (log/debug (i18n/trs "Making connection to {0}" server))
            (ws/connect server
                        :client websocket-client
                        :on-connect (fn [session]
                                      (log/debug (i18n/trs "WebSocket connected"))
                                      (let [message (session-association-message client)
                                            buffer  (ByteBuffer/wrap (message/encode message))]
                                        (.. session (getRemote) (sendBytes buffer)))
                                      (log/debug (i18n/trs "Sent associate session request")))
                        :on-error (fn [error]
                                    (log/error error (i18n/trs "WebSocket error")))
                        :on-close (fn [code message]
                                    (log/debug (i18n/trs "WebSocket closed {0} {1}" code message))
                                    (reset! associate-response (promise))
                                    (let [{:keys [should-stop websocket-connection]} client]
                                      (when (not (realized? should-stop))
                                        (reset! websocket-connection (future (make-connection client))))))
                        :on-receive (fn [text]
                                      (log/debug (i18n/trs "Received text message"))
                                      (dispatch-message client (message/decode (message/string->bytes text))))
                        :on-binary (fn [buffer offset count]
                                     (let [message (message/decode buffer)]
                                       (log/debug (i18n/trs "Received bin message - offset/bytes: {0}/{1} - message: {2}"
                                                            offset count message))
                                       (dispatch-message client message))))
            (catch javax.net.ssl.SSLHandshakeException exception
              (log/warn exception (i18n/trs "TLS Handshake failed.  Sleeping for up to {0}ms to retry" retry-sleep))
              (deref should-stop retry-sleep nil))
            (catch java.net.ConnectException exception
              ;; The following will produce "Didn't get connected.  ..."
              ;; The apostrophe needs to be duplicated (enven in the translations).
              (log/debug exception (i18n/trs "Didn''t get connected.  Sleeping for up to {0}ms to retry" retry-sleep))
              (deref should-stop retry-sleep nil))
            (catch Object _
              (log/error (:throwable &throw-context) (i18n/trs "Unexpected error"))
              (throw+)))
          (recur (min maximum-sleep (* retry-sleep sleep-multiplier)))))))


(s/defn -wait-for-connection :- (s/maybe Client)
  "Waits until a client is connected.  If timeout is hit, returns falsey"
  [client :- Client timeout :- s/Num]
  (let [{:keys [websocket-connection]} client]
    (if (deref @websocket-connection timeout nil)
      client
      nil)))

(s/defn -wait-for-association :- (s/maybe Client)
  "Waits until a client is associated.  If timeout is hit, or the association doesn't work, returns falsey"
  [client :- Client timeout :- s/Num]
  (let [{:keys [associate-response]} client]
    (if (deref @associate-response timeout nil)
      client
      nil)))

(s/defn ^:private -send! :- s/Bool
  "Send a message across the websocket session"
  [client :- Client message :- message/Message]
  (let [{:keys [identity websocket-connection]} client]
    (if-not (-associated? client)
      (throw+ {:type ::not-associated})
      (ws/send-msg @@websocket-connection
                   (message/encode (assoc message :sender identity))))
    true))

(s/defn -close :- s/Bool
  "Close the connection.  Prevent any reconnection attempt 1) by the concurrent
  'connect' task, in case it's still executing, (NB: the 'connect' function
  operates asynchronously by invoking 'make-connection' in a separate thread)
  or 2) by the :on-close event handler.  Stop the heartbeat thread."
  [client :- Client]
  (log/debug (i18n/trs "Closing"))
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
   :type s/Str
   (s/optional-key :user-data) s/Any})

;; private helpers for the ssl/websockets setup
(s/defn ^:private make-ssl-context :- SslContextFactory
  "Returns an SslContextFactory that does client authentication based on the
  client certificate named"
  [params]
  (let [factory (SslContextFactory.)
        {:keys [cert private-key cacert]} params
        ssl-context (ssl-utils/pems->ssl-context cert private-key cacert)]
    (.setSslContext factory ssl-context)
    (.setNeedClientAuth factory true)
    (.setEndpointIdentificationAlgorithm factory "HTTPS")
    factory))

(s/defn ^:private make-websocket-client :- WebSocketClient
  "Returns a WebSocketClient with the correct SSL context"
  [params :- ConnectParams]
  (let [client (WebSocketClient. (make-ssl-context params))]
    (.start client)
    client))

(s/defn connect :- Client
  "Asyncronously establishes a connection to a pcp-broker named by
  `:server`.  Returns a Client"
  [params :- ConnectParams handlers :- Handlers]
  (let [{:keys [cert type server user-data]} params
        client (map->Client {:server server
                             :identity (make-identity cert type)
                             :websocket-client (make-websocket-client params)
                             :websocket-connection (atom (future true))
                             :associate-response (atom (promise))
                             :handlers (assoc handlers
                                              "http://puppetlabs.com/associate_response" associate-response-handler)
                             :should-stop (promise)
                             :user-data user-data})
        {:keys [websocket-connection]} client]
    (reset! websocket-connection (future (make-connection client)))
    client))
