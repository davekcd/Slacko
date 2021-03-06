(ns slacko.core
  (:gen-class))

(use 'slack-rtm.core)
(use '[clojure.java.shell :only [sh]])
(require '[rethinkdb.query :as r])
(require '[clojure.string :as str])

(if-let
  [token (System/getenv "SLACK_TOKEN")]
  (def rtm-conn (connect token))
  (throw (Exception. "Set API key as $SLACK_TOKEN environment variable")))

(defn parse-deploy-string [deploy-string]
  (zipmap [:deploy :target :branch] (str/split deploy-string #" "))
)

(defn send-message
  ([message] (send-message message "general"))
  ([message room]
  (send-event (:dispatcher rtm-conn)
              {:type "message"
               :text message
               :channel (->> rtm-conn :start :channels (filter #(= (:name %) room)) first :id)
               :user (->> rtm-conn :start :users (filter :is_bot) :id)})))

(defn deploy [message]
  (let [vars (parse-deploy-string (:text message))]
  (get :errors (sh "/bin/sh" "deploy.sh" (:target vars) (:branch vars) (send-message "Deploy successful.")))))

(defn log-message [message]
  (with-open [conn (r/connect :host "127.0.0.1" :port 28015 :db "slack")]
    (-> (r/db "slack")
        (r/table "messages")
        (r/insert message)
        (r/run conn))))

(defn message-handler [message]
  ;; Routes commands to functions
  (log-message message)
  (let [message-text (:text message)]
    (case (first (re-find #"^!([\w]*)" message-text))
      "!deploy" (deploy message)
      nil)))

(def events-publication (:events-publication rtm-conn))
(def message-receiver message-handler)

(defn -main
  [& args]
  (sub-to-event events-publication :message message-receiver)
)
