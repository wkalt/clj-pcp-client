(ns puppetlabs.cthun.client-test
  (require [clojure.test :refer :all]
           [puppetlabs.cthun.client :refer :all]
           [puppetlabs.cthun.message :as message]))

(deftest session-association-message-test
  (let [message (session-association-message {:identity "cth://lolcathost/agent"})]
    (testing "it yields a message"
      (is (map? message)))
    (testing "message with the correct type"
      (is (= "http://puppetlabs.com/associate_request"
             (:message_type message))))
    (testing "message with the right sender"
      (is (= "cth://lolcathost/agent"
             (:sender message))))))

(deftest dispatch-message-test
  (with-redefs [puppetlabs.cthun.client/fallback-handler (fn [c m] "fallback")]

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
