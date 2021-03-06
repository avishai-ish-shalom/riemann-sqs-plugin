(ns riemann-sqs-plugin.core
	(:import [java.util.concurrent Executors])
	(:require
			[amazonica.aws.sqs :as sqs]
			[riemann.core :as core]
			[riemann.service :refer [Service ServiceEquiv]]
			[clojure.tools.logging :refer [warn error info infof debug]]
			[clojure.set :refer [rename-keys]]
			[clojure.string :as string]
      [riemann.config :refer [service!]]
      [cheshire.core :as json]
		))

(def mandatory-opts [:access-key :secret-key :queue-url])
(def default-opts {:concurrency 1, :max-number-of-messages 5, :wait-time-seconds 10, :parser-fn #(json/parse-string % true) :delete-all false})
(def run-flag (atom false))

(defn- parse-message
  "Safely run the parser function and verify the resulting event"
  [parser-fn message]
  (debug "Parsing message with parser function" message)
  (try
    (let [event (parser-fn message)]
      (if (and (instance? clojure.lang.Associative event) (every? keyword? (keys event)))
        event
        (do
          (warn "Check yer parser, message not parsed to a proper map like object. Dropping" event)
          nil)))
    (catch Exception e
      (warn e "Failed to parse message" message))))

(defn- make-message-handler [parser-fn core]
  (fn message-handler [message]
    (when-let [event (parse-message parser-fn (:body message))]
      (debug "Injecting event into core" event)
      (try
        (core/stream! @core event)
        message
        (catch Exception e
          (warn e "Failed to inject event into core" event))))))

(defn- ^{:testable true} consume
  [core {:keys [parser-fn delete-all] :as consumer-opts} sqs-receive-messages sqs-delete-message-batch]
  (try
    (let [message-handler (make-message-handler parser-fn core)
          messages (:messages (sqs-receive-messages))
         ]
      (when (seq messages)
        (let [processed-messages (mapv message-handler messages) ; use mapv instead of map to avoid lazy evaluation
              messages-to-delete (remove nil? (if delete-all messages processed-messages))
             ]
          (when (seq messages-to-delete)
            (debug "Deleting messages from SQS" messages-to-delete)
            (sqs-delete-message-batch messages-to-delete)))))
    (catch Exception e
      (error e "Failed to read from SQS"))))

(defn- ^{:testable true} consume-forever
  "Consumer loop"
  [run-flag & args] (while @run-flag (apply consume args)))


; the plugin record implements ServiceEquiv and Service protocols
; ServiceEquiv is required to avoid re-creating the thread pool on every core reload
(defrecord SQSInput [opts core killer]
  ServiceEquiv
  (equiv? [this {other-opts :opts}]
    (= opts other-opts))
  Service
  (conflict? [this other] false)
  (reload! [this new-core]
    (reset! core new-core))
  (start! [{{:keys [concurrency queue-url]} :opts :as this}]
    (locking this
      (reset! run-flag true)
      (infof "Starting fixed thread pool executor with concurrency %d" concurrency)
      (let [ executor (Executors/newFixedThreadPool concurrency)
             sqs-opts (select-keys opts [:queue-url :max-number-of-messages :wait-time-seconds])
             consumer-opts (select-keys opts [:parser-fn :delete-all])
             creds (select-keys opts [:region :access-key :secret-key])
           ]
        (dotimes [_ concurrency]
          (.submit executor (partial
                              consume-forever
                              run-flag
                              core
                              consumer-opts
                              (partial sqs/receive-message creds sqs-opts)
                              (fn sqs-delete-message-batch [messages]
                                (sqs/delete-message-batch creds :queue-url queue-url :entries (map #(rename-keys % {:message-id :id}) messages))))))
        (reset! killer (fn [] (.shutdown executor))))))
  (stop! [this]
    (reset! run-flag false)
    (when @killer
      (@killer)
      (reset! killer nil))))

(defn sqs-consumer
	"Create an SQS consumer instance"
	[opts]
  {:pre	[(every? opts mandatory-opts)]}
  (let [opts (merge default-opts opts)]
  	(service! (SQSInput. opts (atom nil) (atom nil)))))
