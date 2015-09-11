(ns puppetlabs.pcp.client-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pcp.client :refer :all]
            [puppetlabs.pcp.message :as message]))

;; aliases to 'private' functions
(def session-association-message #'puppetlabs.pcp.client/session-association-message)

(defn make-test-client
  "A dummied up client object"
  []
  {:server ""
   :cacert ""
   :cert ""
   :private-key ""
   :type ""
   :identity "pcp://the_identity/the_type"
   :conn ""
   :state (atom :connecting)
   :websocket ""
   :handlers {}
   :heartbeat-stop (promise)})

(deftest state-checkers-test
  (let [client (make-test-client)]
    (testing "successfully returns positive"
      (is (connecting? client)))
    (testing "successfully returns negative"
      (is (not (open? client))))))

(deftest session-association-message-test
  (let [message (session-association-message (make-test-client))]
    (testing "it yields a message"
      (is (map? message)))
    (testing "message with the correct type"
      (is (= "http://puppetlabs.com/associate_request"
             (:message_type message))))))

(deftest dispatch-message-test
  (with-redefs [puppetlabs.pcp.client/fallback-handler (fn [c m] "fallback")]
    (testing "should fall back to fallback-handler with no :default"
      (is (= "fallback"
             (dispatch-message {:handlers {}} {:message_type "foo"})))))

  (let [client {:handlers {"foo" (fn [c m] "foo")
                           :default (fn [c m] "default")}}]

    (testing "default handler should match when supplied"
      (is (= "foo"
             (dispatch-message client {:message_type "foo"})))
      (is (= "default"
             (dispatch-message client {:message_type "bar"}))))))

(def make-identity #'puppetlabs.pcp.client/make-identity)

(deftest make-identity-test
  (is (= "pcp://broker.example.com/test"
         (make-identity "test-resources/ssl/certs/broker.example.com.pem" "test"))))
