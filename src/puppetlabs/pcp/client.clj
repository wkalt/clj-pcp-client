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

;; WebSocket connection state values

(def ws-states #{:connecting
                 :open
                 :closing
                 :closed})

(defn ws-state? [x] (contains? ws-states x))

;; schemas

(def ConnectParams
  "schema for connection parameters"
  {:server s/Str
   :cacert s/Str
   :cert s/Str
   :private-key s/Str
   :type s/Str})

(def WSState
  "schema for an atom referring to a WebSocket connection state"
  (s/pred (comp ws-state? deref)))

(def Handlers
  "schema for handler map.  String keys are data_schema handlers,
  keyword keys are special handlers (like :default)"
  {(s/either s/Str s/Keyword) (s/pred fn?)})

(def Client
  "schema for a client"
  (merge ConnectParams
         {:identity p/Uri
          :state WSState
          :handlers Handlers
          :should-stop Object ;; promise that when delivered means should stop
          :websocket-client Object
          :websocket-connection Object ;; atom of a promise that will be a connection or true
          }))

;; connection state checkers
(s/defn ^:always-validate state :- (s/pred ws-state?)
  [client]
  @(:state client))

(s/defn ^:always-validate connecting? :- s/Bool
  [client :- Client]
  (= (state client) :connecting))

(s/defn ^:always-validate open? :- s/Bool
  [client :- Client]
  (= (state client) :open))

(s/defn ^:always-validate closing? :- s/Bool
  [client :- Client]
  (= (state client) :closing))

(s/defn ^:always-validate closed? :- s/Bool
  [client :- Client]
  (= (state client) :closed))

;; private helpers for the ssl/websockets setup

(defn- make-ssl-context
  "Returns an SslContextFactory that does client authentication based on the
  client certificate named"
  ^SslContextFactory
  [params]
  (let [factory (SslContextFactory.)
        {:keys [cert private-key cacert]} params
        ssl-context (ssl-utils/pems->ssl-context cert private-key cacert)]
    (.setSslContext factory ssl-context)
    (.setNeedClientAuth factory true)
    factory))

(defn- make-websocket-client
  "Returns a WebSocketClient with the correct SSL context"
  ^WebSocketClient
  [params]
  (let [client (WebSocketClient. (make-ssl-context params))]
    (.start client)
    client))

(s/defn ^:always-validate ^:private session-association-message :- Message
  [client :- Client]
  (-> (message/make-message :message_type "http://puppetlabs.com/associate_request"
                            :targets ["pcp:///server"])
      (message/set-expiry 3 :seconds)))

(defn fallback-handler
  "The handler to use when no handler matches"
  [client message]
  (log/debug "no handler for " message))

(defn dispatch-message
  [client message]
  (let [message-type (:message_type message)
        handlers (:handlers client)
        handler (or (get handlers message-type)
                    (get handlers :default)
                    fallback-handler)]
    (handler client message)))

;; synchronous interface

(s/defn ^:always-validate send! :- s/Bool
  "Send a message across the websocket session"
  [client :- Client message :- message/Message]
  (let [{:keys [identity websocket-connection]} client]
    (if-not (and (realized? @websocket-connection) (not (= true @@websocket-connection)))
      (throw+ {:type ::not-connected})
      (ws/send-msg @@websocket-connection
                   (message/encode (assoc message :sender identity))))
    true))

(s/defn ^:always-validate ^:private make-identity :- p/Uri
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
  (let [{:keys [server websocket-client state should-stop]} client
        initial-sleep 200
        sleep-multiplier 2
        maximum-sleep (* 30 1000)]
    (reset! state :connecting)
    (loop [retry-sleep initial-sleep]
      (or (try+
            (log/debugf "Making connection to %s" server)
            (ws/connect server
                        :client websocket-client
                        :on-connect (fn [session]
                                      (log/debug "WebSocket connected")
                                      (reset! state :open)
                                      (send! client (session-association-message client))
                                      (log/debug "sent associate session request"))
                        :on-error (fn [error]
                                    (log/error error "WebSocket error"))
                        :on-close (fn [code message]
                                    (log/debug "WebSocket closed" code message)
                                    (reset! state :closed)
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

(s/defn ^:always-validate connect :- Client
  [params :- ConnectParams handlers :- Handlers]
  (let [{:keys [cert type]} params
        client (merge params {:identity (make-identity cert type)
                              :state (atom :connecting :validator ws-state?)
                              :websocket-client (make-websocket-client params)
                              :websocket-connection (atom (future true))
                              :handlers handlers
                              :should-stop (promise)})
        {:keys [websocket-connection]} client]
    (.start (Thread. (partial heartbeat client)))
    (reset! websocket-connection (future (make-connection client)))
    client))

(s/defn ^:always-validate wait-for-connection :- (s/maybe Client)
  "Waits until a client is connected.  If timeout is hit, returns falsey"
  [client :- Client timeout :- s/Num]
  (let [{:keys [websocket-connection]} client]
    (if (deref @websocket-connection timeout nil)
      client
      nil)))

(s/defn ^:always-validate close :- s/Bool
  "Close the connection"
  [client :- Client]
  (let [{:keys [should-stop websocket-client websocket-connection]} client]
    ;; NOTE:  This true value is also the sentinel for make-connection
    (deliver should-stop true)
    (when (contains? #{:opening :open} (state client))
      (log/debug "Closing")
      (reset! (:state client) :closing)
      (.stop websocket-client))
    (if (and (realized? @websocket-connection) (not (= true @@websocket-connection)))
      (ws/close @@websocket-connection)))
  true)
