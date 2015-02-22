;;Copyright 2014 Istvan Szukacs

;;Licensed under the Apache License, Version 2.0 (the "License");
;;you may not use this file except in compliance with the License.
;;You may obtain a copy of the License at

;;    http://www.apache.org/licenses/LICENSE-2.0

;;Unless required by applicable law or agreed to in writing, software
;;distributed under the License is distributed on an "AS IS" BASIS,
;;WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;See the License for the specific language governing permissions and
;;limitations under the License
(ns shovel.core
  (:require
    ;internal
    [shovel.consumer        :as     sh-consumer                 ]
    [shovel.producer        :as     sh-producer                 ]
    [shovel.helpers         :refer   :all                       ]
    ;external
    [clojure.tools.logging  :as     log                         ]
    [metrics.meters         :refer  [defmeter mark! rates]      ]
    [metrics.core           :refer  [new-registry]              ]
    [clojure.core.async     :as     async         ]
    [clojure.tools.cli      :refer  [parse-opts]  ])
  (:import 
    [java.io                File                                ]
    [java.util              ArrayList                           ]
    [kafka.consumer         ConsumerConfig Consumer KafkaStream ]
    [kafka.javaapi.consumer ConsumerConnector                   ]
    [kafka.message          MessageAndMetadata                  ])
  (:gen-class))

;; metrics 
(def reg (new-registry))
(defmeter reg messages-read)
(defmeter reg messages-written)

;; Helpers
;;
(defn default-iterator
  "processing all streams in a thread and printing the message field for each message"
  [^ArrayList streams]
  (let [c (async/chan)]
    ;; create a thread for each stream
    (doseq
      [^KafkaStream stream streams]
      (let [uuid (uuid)]
        (async/thread
          (async/>!! c
            (doseq
              [^MessageAndMetadata message stream]
              (sh-consumer/message-to-vec message))))))
    ;; read the channel forever
    (while true
      (async/<!! c))))

;; OPS

(defn test-consumer
  [config topic] 
  (log/info "fn: test-consumer params: " config topic)
  (default-iterator
    (sh-consumer/message-streams
      (sh-consumer/consumer-connector config)
      topic
      (int 2))))

(defn test-producer
  [config topic]
  (log/info "fn: test-producer params: " config)
  (let [producer-connection (sh-producer/producer-connector config) counter (atom 0)]
    (doseq [n (range 1000000)]
      (do
        (mark! messages-written)
        (log/debug n)
        (cond (= @counter 100000) (do (reset! counter 0) (log/info (rates messages-written))) :else (do (log/debug @counter) (swap! counter inc)))
        (sh-producer/produce
          producer-connection
          ;move this to config
          (sh-producer/message topic "asd" (str "this is my message" n))))))
  
  (log/info {:ok :ok}))


(defn new-consumer-messages 
  [config] 
  (log/info "fn: new-consumer-messages params: " config)
  (let [  config  (get-in config [:ok :consumer-config]) 
          topic   (get-in config [:ok :common :consumer-topic])
          message-stream (sh-consumer/messages
                          (sh-consumer/message-streams 
                            (sh-consumer/consumer-connector config) 
                            "shovel-test-0"
                            (int 2))) 
          counter (atom 0)                                              ]

        (doseq [message message-stream]
          (do 
            (mark! messages-read)
            (cond (= @counter 100000) (do (reset! counter 0) (log/info (rates messages-read))) :else (do (log/debug @counter) (swap! counter inc)))
            (log/debug message)))))
            
(defn end-to-end [config]
  (async/thread 
    (test-producer (get-in config [:ok :producer-config]) (get-in config [:ok :common :producer-topic])))
  (new-consumer-messages config))
    
;; CLI

(def cli-options
  [
    ["-f" "--config-file FILE" "Configuration file" :default "conf/app.edn"]
    ["-c" "--connect" "Initiate connections" :default false ]
    ["-h" "--help" "This application is helpless"]
  ])

(defn -main [& args]
  (log/info "-main starts")
  (let [  {:keys [options arguments errors summary]} (parse-opts args cli-options)
          config (read-config (:config-file options))                               ]
    ;; INIT
    (log/info "init :: start")
    (log/info "checking config...")
    (cond 
      (contains? config :ok)
        (config-ok config)
      :else
        ;; exit 1 here
        (config-err config))


    ; Execute program with options
    (case (first arguments)
      "print-config"
        (println config)
      "consumer-test"
        (test-consumer (get-in config [:ok :consumer-config]) (get-in config [:ok :common :consumer-topic]))
      "producer-test"
        (test-producer (get-in config [:ok :producer-config]) (get-in config [:ok :common :producer-topic]))
      "end-to-end"
        (end-to-end config)
      "new-consumer-messages"
        (new-consumer-messages config)
      ;default
        (do
          (log/error "Missing arugments")
          (exit 1)))))

;; END
