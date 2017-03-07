(ns puppetlabs.pcp.client
  (:require [clojure.tools.logging :as log]
            [gniazdo.core :as ws]
            [puppetlabs.pcp.message-v2 :as message :refer [Message]]
            [puppetlabs.pcp.protocol :as p]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [schema.core :as s]
            [puppetlabs.i18n.core :as i18n])
  (:use [slingshot.slingshot :only [throw+ try+]])
  (:import  (clojure.lang Atom)
            (java.nio ByteBuffer)
            (org.eclipse.jetty.websocket.client WebSocketClient)
            (org.eclipse.jetty.util.ssl SslContextFactory)
            (javax.net.ssl SSLContext)
            (java.security KeyStore)))

(defprotocol ClientInterface
  "client interface - make one with connect"
  (connecting? [client]
    "Returns true if the client is currently connecting to the pcp-broker")
  (connected? [client]
    "Returns true if the client is currently connected to the pcp-broker.
    Propagates any unhandled exception thrown while attempting to connect.")
  (wait-for-connection [client timeout-ms]
    "Wait up to timeout-ms for a connection to be established.
    Returns the client if the connection has been established, else nil.
    Propagates any unhandled exception thrown while attempting to connect.")
  (send! [client message]
    "Send a message across the currently connected client. Will
    raise ::not-associated if the client is not currently associated with the
    pcp-broker, and ::not-connected if the client has become disconnected from
    the pcp-broker.")
  (close [client]
    "Close the connection. Once the client is close you will need a
    new one.

     NOTE: you must invoke this function to properly close the connection,
     otherwise reconnection attempts may happen and the heartbeat thread will
     persist, in case it was previously started."))

(def Handlers
  "schema for handler map. String keys are data_schema handlers,
  keyword keys are special handlers (like :default)"
  {(s/either s/Str s/Keyword) (s/pred fn?)})

;; forward declare implementations of protocol functions. We prefix
;; with the dash so they're not clashing with the versions defined by
;; ClientInterface
(declare -connecting? -connected?
         -wait-for-connection -send! -close)

(s/defrecord Client
  [server :- s/Str
   handlers :- Handlers
   on-close-cb
   on-connect-cb
   should-stop ;; promise that when delivered means should stop
   websocket-connection ;; atom of a promise that will be a connection or true
   websocket-client]
  {(s/optional-key :user-data) s/Any} ;; a field for user data
  ClientInterface
  (connecting? [client] (-connecting? client))
  (connected? [client] (-connected? client))
  (wait-for-connection [client timeout] (-wait-for-connection client timeout))
  (send! [client message] (-send! client message))
  (close [client] (-close client)))

(s/defn ^:private -connecting? :- s/Bool
  [client :- Client]
  (let [{:keys [websocket-connection]} client]
    (not (realized? @websocket-connection))))

(s/defn ^:private -connection-connected? :- s/Bool
  [connection]
  (and (realized? connection) (not (= @connection true))))

(s/defn ^:private -connected? :- s/Bool
  [client :- Client]
  (let [{:keys [websocket-connection]} client]
    ;; if deref returned an exception, we have no connection
    ;; this happens occasionally if the broker is starting up or shutting down
    ;; when we attempt to connect, or if the connection disappears abruptly
    (try
      (-connection-connected? @websocket-connection)
      (catch java.util.concurrent.ExecutionException exception
        (log/debug exception (i18n/trs "exception while establishing a connection; not connected"))
        false))))

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

(s/defn ^:private heartbeat
  "Provides the WebSocket heartbeat task that sends pings over the
  current set of connections as long as the 'should-stop' promise has
  not been delivered. Will keep a connection alive, or detect a
  stalled connection earlier."
  [websocket-client should-stop]
  (while (not (deref should-stop 15000 false))
    (let [sessions (.getOpenSessions websocket-client)]
      (log/debug (i18n/trs "Sending WebSocket ping"))
      (doseq [session sessions]
        (.. session (getRemote) (sendPing (ByteBuffer/allocate 1))))))
  (log/debug (i18n/trs "WebSocket heartbeat task is about to finish")))

(s/defn ^:private make-connection :- Object
  "Returns a connected WebSocket connection. In case of a SSLHandShakeException
  or ConnectException a further connection attempt will be made by following an
  exponential backoff, whereas other exceptions will be propagated."
  [client :- Client]
  (let [{:keys [server websocket-client should-stop on-close-cb on-connect-cb]} client
        initial-sleep 200
        sleep-multiplier 2
        maximum-sleep (* 15 1000)]
    ;; Use a separate promise for ensuring the heartbeat stops when connection is closed.
    ;; If a new connection is established, a new heartbeat thread will start.
    (loop [retry-sleep initial-sleep
           stop-heartbeat (promise)]
      (println "attempting connection")
      (or (try+
            (log/debug (i18n/trs "Making connection to {0}" server))
            (ws/connect server
                        :client websocket-client
                        :on-connect (fn [session]
                                      (log/debug (i18n/trs "WebSocket connected, heartbeat task starting"))
                                      (.start (Thread. (partial heartbeat websocket-client stop-heartbeat)))
                                      (when on-connect-cb (on-connect-cb client)))
                        :on-error (fn [error]
                                    (println "RECEIVED ERROR")
                                    (log/error error (i18n/trs "WebSocket error")))
                        :on-close (fn [code message]
                                    (println "lost connection" code message)
                                    ;; Format error code as a string rather than a localized number, i.e. 1,234.
                                    (log/debug (i18n/trs "WebSocket closed {0} {1}" (str code) message))
                                    (deliver stop-heartbeat true)
                                    (when on-close-cb (on-close-cb client))
                                    (let [{:keys [should-stop websocket-connection]} client]
                                      ;; Ensure disconnect state is immediately registered as connecting.
                                      (log/debug (i18n/trs "Sleeping for up to {0} ms to retry" retry-sleep))
                                      (println "Sleeping ms to retry" retry-sleep)
                                      (reset! websocket-connection
                                              (future
                                                (if-not (deref should-stop initial-sleep nil)
                                                  (make-connection client)
                                                  true)))))
                        :on-receive (fn [text]
                                      (log/debug (i18n/trs "Received text message"))
                                      (dispatch-message client (message/decode text))))
            (catch javax.net.ssl.SSLHandshakeException exception
              (println "CAUGHT SSLHANDSHAKE" exception)
              (log/warn exception (i18n/trs "TLS Handshake failed. Sleeping for up to {0} ms to retry" retry-sleep))
              (deref should-stop retry-sleep nil))
            (catch java.net.ConnectException exception
              (println "CAUGHT CONN EX" exception)
              ;; The following will produce "Didn't get connected. ..."
              ;; The apostrophe needs to be duplicated (even in the translations).
              (log/debug exception (i18n/trs "Didn''t get connected. Sleeping for up to {0} ms to retry" retry-sleep))
              (println "Didn''t get connected. Sleeping for up to {0} ms to retry" retry-sleep exception)
              (deref should-stop retry-sleep nil))
            (catch java.io.IOException exception
              (println "CAUGHT IOEX" exception)
              (log/debug exception (i18n/trs "Connection closed while establishing connection. Sleeping for up to {0} ms to reconnect" retry-sleep))
              (deref should-stop retry-sleep nil))
            (catch Object _
              (println "CAUGHT OBJ" _)
              (log/error (:throwable &throw-context) (i18n/trs "Unexpected error"))
              (throw+)))
          (recur (min maximum-sleep (* retry-sleep sleep-multiplier)) (promise))))))

(s/defn -wait-for-connection :- (s/maybe Client)
  "Waits until a client is connected. If timeout is hit, returns falsey"
  [client :- Client timeout :- s/Num]
  (let [{:keys [websocket-connection]} client]
    (try
      (when (deref @websocket-connection timeout nil)
        client)
      (catch java.util.concurrent.ExecutionException exception
        (log/debug exception (i18n/trs "exception while waiting for a connection; not connected"))))))

(s/defn ^:private -send! :- s/Bool
  "Send a message across the websocket session"
  [client :- Client message :- message/Message]
  (let [{:keys [websocket-connection]} client]
    (if-not (connected? client)
      (do (log/debug (i18n/trs "refusing to send message on unconnected session"))
          (throw+ {:type ::not-connected}))
      (try
        (ws/send-msg @@websocket-connection (message/encode message))
        (catch java.util.concurrent.ExecutionException exception
          (log/debug exception (i18n/trs "exception on the connection while attempting to send a message"))
          (throw+ {:type ::not-connected}))))
    true))

(s/defn -close :- s/Bool
  "Close the connection. Prevent any reconnection attempt 1) by the concurrent
  'connect' task, in case it's still executing, (NB: the 'connect' function
  operates asynchronously by invoking 'make-connection' in a separate thread)
  or 2) by the :on-close event handler. Stop the heartbeat thread."
  [client :- Client]
  (log/debug (i18n/trs "Closing"))
  (let [{:keys [should-stop websocket-client websocket-connection]} client]
    ;; NOTE:  This true value is also the sentinel for make-connection
    (println "CLOSING CONNECTION")
    (deliver should-stop true)
    (try
      ;; Make access to websocket-connection atomic, so we don't cause an exception when racing
      ;; during shutdown. The websocket-connection atom could be reset to true between checking
      ;; connected? and dereferencing the atom to close the connection.
      (let [connection @websocket-connection]
        (if (-connection-connected? connection)
          (ws/close @connection)))
      (catch java.util.concurrent.ExecutionException exception
        (log/debug exception (i18n/trs "exception while closing the connection; connection already closed"))))
    (.stop websocket-client))
  true)

(def SslFiles
  {:cacert s/Str
   :cert s/Str
   :private-key s/Str})

(def ConnectParams
  "schema for connection parameters"
  {:server s/Str
   :ssl-context (s/either SslFiles SSLContext)
   (s/optional-key :on-close-cb) Object
   (s/optional-key :on-connect-cb) Object
   (s/optional-key :type) s/Str
   (s/optional-key :user-data) s/Any
   (s/optional-key :max-message-size) s/Int})

(defmulti get-ssl-context class)
(defmethod get-ssl-context SSLContext [context] context)
(defmethod get-ssl-context java.util.Map [ssl-files]
  (let [{:keys [cert private-key cacert]} ssl-files]
    (ssl-utils/pems->ssl-context cert private-key cacert)))

;; private helpers for the ssl/websockets setup
(s/defn ^:private make-ssl-context :- SslContextFactory
  "Returns an SslContextFactory that does client authentication based on the
  client certificate named"
  [params]
  (let [factory (SslContextFactory.)
        ssl-context (get-ssl-context (:ssl-context params))]
    (.setSslContext factory ssl-context)
    (.setNeedClientAuth factory true)
    (.setEndpointIdentificationAlgorithm factory "HTTPS")
    factory))

(s/defn ^:private make-websocket-client :- WebSocketClient
  "Returns a WebSocketClient with the correct SSL context"
  [ssl-context :- SslContextFactory max-message-size :- (s/maybe s/Int)]
  (let [client (WebSocketClient. ssl-context)]
    (if max-message-size
      (.setMaxTextMessageSize (.getPolicy client) max-message-size))
    (.start client)
    client))

(defn ^:private append-client-type
  "Append client type to a ws connection Uri, accounting for possible trailing
   slash."
  [url type]
  (if (clojure.string/ends-with? url "/")
    (str url type)
    (str url "/" type)))

(s/defn connect :- Client
  "Asyncronously establishes a connection to a pcp-broker named by
  `:server`. Returns a Client.

   The certificate file specified can provide either a single certificate,
   or a certificate chain (with the first entry being the client's certificate)."
  [params :- ConnectParams handlers :- Handlers]
  (let [{:keys [cert type server user-data]} params
        defaulted-type (or type "agent")
        ssl-context-factory (make-ssl-context params)
        client (map->Client {:server (append-client-type server defaulted-type)
                             :websocket-client (make-websocket-client ssl-context-factory
                                                                      (:max-message-size params))
                             :websocket-connection (atom (future true))
                             :on-close-cb (:on-close-cb params)
                             :on-connect-cb (:on-connect-cb params)
                             :handlers handlers
                             :should-stop (promise)
                             :user-data user-data})
        {:keys [websocket-connection]} client]
    (reset! websocket-connection (future (make-connection client)))
    client))
