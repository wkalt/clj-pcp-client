(ns puppetlabs.pcp.client-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.client :refer :all :as client]
            [puppetlabs.pcp.message :as message]
            [slingshot.test]))

(defn make-test-client
  "A dummied up client object"
  ([user-data]
   (map->Client {:server "wss://localhost:8142/pcp/v1"
                 :identity "pcp://the_identity/the_type"
                 :websocket-client ""
                 :websocket-connection (atom (future true))
                 :associate-response (atom (promise))
                 :handlers {}
                 :should-stop (promise)
                 :user-data user-data}))
  ([]
   (make-test-client nil)))

(deftest client-with-user-data-test
  (let [client (make-test-client "foo")]
    (testing "client includes the user data"
      (is (= (:user-data client) "foo")))))

(deftest state-checkers-test
  (let [client (make-test-client)]
    (testing "successfully returns negative"
      (is (not (connected? client))))
    (testing "successfully returns negative"
      (is (not (connecting? client))))))

(def session-association-message #'puppetlabs.pcp.client/session-association-message)
(deftest session-association-message-test
  (let [message (session-association-message (make-test-client))]
    (testing "it yields a message"
      (is (map? message)))
    (testing "message with the correct type"
      (is (= "http://puppetlabs.com/associate_request"
             (:message_type message))))))

(def associate-response-handler #'puppetlabs.pcp.client/associate-response-handler)
(deftest associate-response-handler-test
  (let [message (message/make-message)
        message (message/set-json-data message {:success true :id (:id message)})
        client (make-test-client)]
    (testing "it delivers the promise"
      (is (= true (client/associating? client)))
      (is (= false (client/associated? client)))
      (associate-response-handler client message)
      (is (= false (client/associating? client)))
      (is (= true (client/associated? client))))
    (testing "it throws on bad message"
      (let [bad-message (message/set-json-data message "badness")]
        (is (thrown+? [:type :schema.core/error]
                      (associate-response-handler client bad-message)))))))

(def dispatch-message #'puppetlabs.pcp.client/dispatch-message)
(deftest dispatch-message-test
  (with-redefs [puppetlabs.pcp.client/fallback-handler (fn [c m] "fallback")]
    (testing "should fall back to fallback-handler with no :default"
      (is (= "fallback"
             (dispatch-message (assoc (make-test-client) :handlers {})
                               (message/make-message :message_type "foo"))))))

  (let [client (assoc (make-test-client)
                      :handlers {"foo" (fn [c m] "foo")
                                 :default (fn [c m] "default")})]

    (testing "default handler should match when supplied"
      (is (= "foo"
             (dispatch-message client (message/make-message :message_type "foo"))))
      (is (= "default"
             (dispatch-message client (message/make-message :message_type "bar")))))))

(def make-identity #'puppetlabs.pcp.client/make-identity)
(deftest make-identity-test
  (is (= "pcp://client01.example.com/test"
         (make-identity "test-resources/ssl/certs/client01.example.com.pem" "test"))))

(def make-connection #'puppetlabs.pcp.client/make-connection)
(deftest make-connection-test
  (with-redefs [gniazdo.core/connect (constantly "awesome")]
    (is (= "awesome"
           (make-connection (make-test-client))))))

(deftest wait-for-connection-test
  (testing "when connected"
    (let [connection (atom (future "yes"))
          connected (assoc (make-test-client) :websocket-connection connection)]
      (is (= connected
             (wait-for-connection connected 1000)))))
  (testing "when connecting slowly"
    (let [connection (atom (future (Thread/sleep 10000) "slowly"))
          connected-later (assoc (make-test-client) :websocket-connection connection)]
      (is (= nil
             (wait-for-connection connected-later 1000))))))
