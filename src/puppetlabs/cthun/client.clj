(ns puppetlabs.cthun.client
  (:require [clojure.tools.logging :as log]
            [gniazdo.core :as ws]
            [puppetlabs.cthun.message :as message]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [schema.core :as s])
  (:import  (java.nio ByteBuffer)
            (org.eclipse.jetty.websocket.client WebSocketClient)
            (org.eclipse.jetty.util.ssl SslContextFactory)))

;; schemas

(def ConnectParams
  "schema for connection parameters"
  {:server s/Str
   :cacert s/Str
   :cert s/Str
   :private-key s/Str
   :identity s/Str
   :type s/Str})

(def Handlers
  "schema for handler map.  String keys are data_schema handlers,
  keyword keys are special handlers (like :default)"
  {(s/either s/Str s/Keyword) (s/pred fn?)})

(def Client
  "schema for a client"
  (merge ConnectParams
         {:conn Object
          :websocket Object
          :handlers Handlers}))

;; private helpers for the ssl/websockets setup

(defn- make-ssl-context
  "Returns an SslContextFactory that does client authentication based on the client certificate named"
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

(defn session-association-message
  [client]
  (-> (message/make-message)
      (message/set-expiry 3 :seconds)
      (assoc :sender (:identity client)
             :message_type "http://puppetlabs.com/associate_request"
             :targets ["cth:///server"])))

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


(s/defn ^:always-validate send! :- s/Bool
  [client :- Client message :- message/Message]
  (ws/send-msg (:conn client)
               (message/encode (assoc message :sender (:identity client))))
  true)

;; TODO(richardc): the identity should be derived from the client
;; certificate and the connection type.

(s/defn ^:always-validate connect :- Client
  [params :- ConnectParams handlers :- Handlers]
  (let [client (promise)
        websocket (make-websocket-client params)
        conn (ws/connect (:server params)
                         :client websocket
                         :on-connect (fn [session]
                                       (log/debug "connected")
                                       (send! @client (session-association-message @client))
                                       (log/debug "sent login"))
                         :on-error (fn [error] (log/error "websockets error" error))
                         :on-close (fn [code message]
                                     (log/debug "websocket closed" code message))
                         :on-receive (fn [text]
                                       (log/debug "text message")
                                       (dispatch-message @client (message/decode (message/string->bytes text))))
                         :on-binary (fn [buffer offset count]
                                      (log/debug "bytes message" offset count)
                                      (log/debug "got " (message/decode buffer))
                                      (dispatch-message @client (message/decode buffer))))]
    (deliver client (assoc params :conn conn :websocket websocket :handlers handlers))
    @client))

(s/defn ^:always-validate close :- s/Bool
  "Close the connection"
  [client :- Client]
  (log/debug "Closing")
  (.stop (:websocket client))
  (ws/close (:conn client))
  true)
