(defn deploy-info
  [url]
  {:url url
   :username :env/nexus_jenkins_username
   :password :env/nexus_jenkins_password
   :sign-releases false})

(defproject puppetlabs/cthun-client "0.0.2-SNAPSHOT"
  :description "client library for cthun protocol"
  :url "https://github.com/puppetlabs/clj-cthun-client"
  :license {:name ""
            :url ""}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.3.1"]

                 [prismatic/schema "0.4.0"]

                 [puppetlabs/ssl-utils "0.8.0"]

                 [puppetlabs/cthun-message "0.0.1"]

                 ;; TODO(richardc) transitive from puppetlabs/cthun-message.  should not be needed
                 [clj-time "0.9.0"]

                 [stylefruits/gniazdo "0.4.0"]

                 ;; try+/throw+
                 [slingshot "0.12.2"]]

  :plugins [[lein-release "1.0.5"]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots"  "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :deploy-repositories [["releases" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/releases/")]
                        ["snapshots" ~(deploy-info "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/")]]
  :lein-release {:scm :git, :deploy-via :lein-deploy})
