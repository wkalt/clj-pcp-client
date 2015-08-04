(ns puppetlabs.cthun.messaging-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.cthun.client :as client]
            [puppetlabs.cthun.message :as message]))

(defn default-request-handler
  [conn request]
  (log/debug "Default handler got message" request))

;; connect a controller with handler functions
(defn connect-controller [controller-id, handler-function] (client/connect
         {:server "wss://localhost:8090/cthun/"
          :cert (str "test-resources/ssl/certs/" controller-id ".pem")
          :private-key (str "test-resources/ssl/private_keys/" controller-id ".pem")
          :cacert "test-resources/ssl/certs/ca.pem"
          :identity (str "cth://" controller-id "/demo-client")
          :type "demo-client"}
          {"example/cnc_request" handler-function
           "example/any_schema" handler-function
            :default default-request-handler}))

(deftest send-message-and-assert-received-unchanged
  (let [expected-data "Hello World!Ѱ$£%^\"\t\r\n(*)"
        actual-data (atom nil)
        expected-id (atom nil)
        actual-id (atom nil)
        expected-expires (atom nil)
        actual-expires (atom nil)
        expected-targets ["cth://0002_controller/demo-client"]
        actual-targets (atom nil)
        expected-sender "cth://0001_controller/demo-client"
        actual-sender (atom nil)
        msg2-received? (promise)
        conn1 (connect-controller "0001_controller" default-request-handler)
        handler (fn
          [conn2 request]
          (log/info "conn2 got message via any_schema" request)
          (let [data (String. (message/get-data request) "UTF-8")]
          (log/debug "message data as string is:" data)
          (reset! actual-data data)
          (reset! actual-id (:id request))
          (reset! actual-expires (:expires request))
          (reset! actual-targets (:targets request))
          (reset! actual-sender (:sender request))
          (deliver msg2-received? true)))
        conn2 (connect-controller "0002_controller" handler)
        ]

  (let [outbound-message (-> (message/make-message)
      (message/set-expiry 3 :seconds)
      (message/set-data (byte-array (.getBytes expected-data "UTF-8")))
      (assoc :targets expected-targets
             :message_type "example/any_schema"))]
    (reset! expected-id (:id outbound-message))
    (reset! expected-expires(:expires outbound-message))
    (client/send! conn1 outbound-message)
  )

  @msg2-received?
  (is (= expected-data @actual-data) (str "Comparing sent data \"" expected-data "\" with received data \"" @actual-data "\""))
  (log/info (str "Asserting that id" @expected-id "equals" @actual-id))
  (is (= @expected-id @actual-id) (str "Comparing sent id \"" @expected-id "\" with received id \"" @actual-id "\""))
  (log/info (str "Asserting that expiry \"" @expected-expires "\" equals \"" @actual-expires "\""))
  (is (= @expected-expires @actual-expires) (str "Comparing sent expires \"" @expected-expires "\" with received expires \"" @actual-expires "\""))
  (log/info (str "Asserting that targets \"" expected-targets "\" equals \"" @actual-targets "\""))
  (is (= expected-targets @actual-targets) (str "Comparing sent targets \"" expected-targets "\" with received targets \"" @actual-targets "\""))
  (log/info (str "Asserting that sender \"" expected-sender "\" equals \"" @actual-sender "\""))
  (is (= expected-sender @actual-sender) (str "Comparing sent sender \"" expected-sender "\" with received sender \"" @actual-sender "\""))
  (client/close conn1)
  (client/close conn2)))
