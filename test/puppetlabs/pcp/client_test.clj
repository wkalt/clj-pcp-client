(ns puppetlabs.pcp.client-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.client :refer :all]
            [puppetlabs.pcp.message :as message]))

(defn make-test-client
  "A dummied up client object"
  []
  (map->Client {:server "wss://localhost:8142/pcp/v1"
                :identity "pcp://the_identity/the_type"
                :state (atom :connecting)
                :websocket-client ""
                :websocket-connection (atom (future true))
                :handlers {}
                :should-stop (promise)}))

(deftest state-checkers-test
  (let [client (make-test-client)]
    (testing "successfully returns positive"
      (is (connecting? client)))
    (testing "successfully returns negative"
      (is (not (open? client))))))

(def session-association-message #'puppetlabs.pcp.client/session-association-message)
(deftest session-association-message-test
  (let [message (session-association-message (make-test-client))]
    (testing "it yields a message"
      (is (map? message)))
    (testing "message with the correct type"
      (is (= "http://puppetlabs.com/associate_request"
             (:message_type message))))))

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
  (is (= "pcp://broker.example.com/test"
         (make-identity "test-resources/ssl/certs/broker.example.com.pem" "test"))))

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
