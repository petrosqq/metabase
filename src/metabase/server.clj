(ns metabase.server
  (:require [clojure
             [core :as core]
             [string :as str]]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase
             [config :as config]
             [util :as u]]
            [metabase.util.i18n :refer [trs]]
            [ring.adapter.jetty :as ring-jetty])
  (:import org.eclipse.jetty.server.Server))

(defn- jetty-ssl-config []
  (m/filter-vals
   some?
   {:ssl-port       (config/config-int :mb-jetty-ssl-port)
    :keystore       (config/config-str :mb-jetty-ssl-keystore)
    :key-password   (config/config-str :mb-jetty-ssl-keystore-password)
    :truststore     (config/config-str :mb-jetty-ssl-truststore)
    :trust-password (config/config-str :mb-jetty-ssl-truststore-password)}))

(defn- jetty-config []
  (cond-> (m/filter-vals
           some?
           {:async?        true
            :port          (config/config-int :mb-jetty-port)
            :host          (config/config-str :mb-jetty-host)
            :max-threads   (config/config-int :mb-jetty-maxthreads)
            :min-threads   (config/config-int :mb-jetty-minthreads)
            :max-queued    (config/config-int :mb-jetty-maxqueued)
            :max-idle-time (config/config-int :mb-jetty-maxidletime)})
    (config/config-str :mb-jetty-daemon) (assoc :daemon? (config/config-bool :mb-jetty-daemon))
    (config/config-str :mb-jetty-ssl)    (-> (assoc :ssl? true)
                                             (merge (jetty-ssl-config)))))

(defn- log-config [jetty-config]
  (log/info (trs "Launching Embedded Jetty Webserver with config:")
            "\n"
            (u/pprint-to-str (m/filter-keys
                              #(not (str/includes? % "password"))
                              jetty-config))))

(defonce ^:private instance*
  (atom nil))

(defn instance
  "*THE* instance of our Jetty web server, if there currently is one."
  ^Server []
  @instance*)

(defn- create-server
  ^Server [handler options]
  (doto ^Server (#'ring-jetty/create-server options)
    (.setHandler (#'ring-jetty/async-proxy-handler handler 0))))

(defn start-web-server!
  "Start the embedded Jetty web server. Returns `:started` if a new server was started; `nil` if there was already a
  running server."
  [handler]
  (when-not (instance)
    ;; NOTE: we always start jetty w/ join=false so we can start the server first then do init in the background
    (let [config     (jetty-config)
          new-server (create-server handler config)]
      (log-config config)
      ;; Only start the server if the newly created server becomes the official new server
      ;; Don't JOIN yet -- we're doing other init in the background; we can join later
      (when (compare-and-set! instance* nil new-server)
        (.start new-server)
        :started))))

(defn stop-web-server!
  "Stop the embedded Jetty web server. Returns `:stopped` if a server was stopped, `nil` if there was nothing to stop."
  []
  (let [[^Server old-server] (reset-vals! instance* nil)]
    (when old-server
      (log/info (trs "Shutting Down Embedded Jetty Webserver"))
      (.stop old-server)
      :stopped)))
