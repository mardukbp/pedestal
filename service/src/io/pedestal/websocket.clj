(ns io.pedestal.websocket
  (:require [clojure.core.async :as async :refer [go-loop put!]]
            [clojure.spec.alpha :as s]
            [io.pedestal.log :as log])
  (:import (io.pedestal.websocket FnEndpoint)
           (jakarta.websocket EndpointConfig SendHandler Session MessageHandler$Whole RemoteEndpoint$Async)
           (jakarta.websocket.server ServerContainer ServerEndpointConfig ServerEndpointConfig$Builder)
           (java.nio ByteBuffer)))

(defn- message-handler
  ^MessageHandler$Whole [session-object f]
  (reify MessageHandler$Whole
    (onMessage [_ message]
      (f session-object message))))

(def ^:private ^String session-object-key "io.pedestal.websocket.session-object")

(defn- make-endpoint-delegate-callback
  "Given a map defining the WebService behavior, returns a function that will ultimately be used
  by the FnEndpoint instance."
  [ws-map]
  (let [{:keys [on-open
                on-close
                on-error
                on-text                                     ;; TODO: rename to `on-string`?
                on-binary]} ws-map
        maybe-invoke-callback (fn [f ^Session session event-value]
                                (when f
                                  (let [session-object (-> session
                                                           .getUserProperties
                                                           (.get session-object-key))]
                                    (f session-object session event-value))))
        full-on-open (fn [^Session session ^EndpointConfig config]
                       (let [session-object (when on-open
                                              (on-open session config))]
                         ;; Store this for on-close, on-error
                         (-> session .getUserProperties (.put session-object-key session-object))

                         (when on-text
                           (.addMessageHandler session String (message-handler session-object on-text)))

                         (when on-binary
                           (.addMessageHandler session ByteBuffer (message-handler session-object on-binary)))))]
    (fn [event-type ^Session session event-value]
      (case event-type
        :on-open (full-on-open session event-value)
        :on-error (maybe-invoke-callback on-error session event-value)
        :on-close (maybe-invoke-callback on-close session event-value)))))

(s/def ::endpoint
  (s/keys :opt-un [::on-open ::on-close ::on-error ::on-text ::on-binary]))

(s/def ::on-open fn?)
(s/def ::on-close fn?)
(s/def ::on-error fn?)
(s/def ::on-text fn?)
(s/def ::on-binary fn?)

(s/def ::path-map
  (s/map-of string? ::endpoint))

(defn add-endpoint
  "Adds a WebSocket endpoint to a ServerContainer.

  The path provides the mapping to the endpoint, and must start with a slash.

  The ws-endpoint-map defines callbacks and configuration for the endpoint.

  When a connection is started for the endpoint, the :on-open callback is invoked; the return value
  is saved as the \"session object\" which is then passed to the remaining callbacks as the first
  function argument.

  :on-open (jakarta.websocket.Session,  jakarta.websocket.EndpointConfig)
  : Invoked when client first opens a connection.  The returned value is retained
    and passed as the first argument of the remaining callbacks.

  :on-close (Object, jakarta.websocket.Session, jakarta.websocket.CloseReason)
  : Invoked when the socket is closed, allowing any resources to be freed.

  :on-error (Object, jakarta.websocket.Session, Throwable)
  : Passed any unexpected exceptions.

  :on-text (Object, String)
  : Passed a text message as a single String.

  :on-binary (Object, java.nio.ByteBuffer)
  : Passed a binary message as a single ByteBuffer.

  All callbacks are optional.  The :on-open callback is critical, as it performs all the one-time setup
  for the WebSocket connection. The [[on-open-start-ws-connection]] function is a good starting place.
  "
  [^ServerContainer container ^String path ws-endpoint-map]
  (let [callback (make-endpoint-delegate-callback ws-endpoint-map)
        config ^ServerEndpointConfig (-> (ServerEndpointConfig$Builder/create FnEndpoint path)
                                         .build)]
    (.put (.getUserProperties config) FnEndpoint/USER_ATTRIBUTE_KEY callback)
    (.addEndpoint container config)))

(defn add-endpoints
  [^ServerContainer container path-map]
  (doseq [[path endpoint] path-map]
    (add-endpoint container path endpoint)))

(defprotocol WebSocketSendAsync
  (ws-send-async [msg remote-endpoint]
    "Sends `msg` to `remote-endpoint`. Returns a
     promise channel from which the result can be taken."))

(defn- ^SendHandler send-handler
  [chan]
  (reify SendHandler
    (onResult [_ result]
      (if (.isOK result)
        (put! chan :success)
        (put! chan (.getException result))))))

(extend-protocol WebSocketSendAsync
  String
  (ws-send-async [msg ^RemoteEndpoint$Async remote-endpoint]
    (let [p-chan (async/promise-chan)]
      (.sendText remote-endpoint msg (send-handler p-chan))
      p-chan))

  ByteBuffer
  (ws-send-async [msg ^RemoteEndpoint$Async remote-endpoint]
    (let [p-chan (async/promise-chan)]
      (.sendBinary remote-endpoint msg (send-handler p-chan))
      p-chan)))

#_(defn on-open-start-ws-connection
    "Given a function of two arguments
    (the Jetty WebSocket Session and its paired core.async 'send' channel),
    and optionally a buffer-or-n for the 'send' channel,
    return a function that can be used as an OnConnect handler.

    Notes:
     - You can control the entire WebSocket Session per client with the
    session object.
     - If you close the `send` channel, Pedestal will close the WS connection."
    ([on-open-fn]
     (on-open-start-ws-connection on-open-fn 10))
    ([on-open-fn send-buffer-or-n]
     (fn [^Session session ^EndpointConfig config]
       (let [send-ch (async/chan send-buffer-or-n)
             async-remote (.getAsyncRemote session)]
         ;; Let's process sends...
         (go-loop []
           (if-let [out-msg (and (.isOpen session)
                                 (async/<! send-ch))]
             (let [ws-send-ch (ws-send-async out-msg async-remote)
                   result (async/<! ws-send-ch)]
               (when-not (= :success result)
                 (log/error :msg "Failed on ws-send-async"
                            :exception result))
               (recur))
             (.close session)))
         (on-open-fn session config send-ch)))))

(defn on-open-start-ws-connection
  "Returns an :on-open callback for [[add-endpoint]] that starts
   an asynchronous connection loop. The returned function itself
   returns a core.async send channel, on which
   values can be put, to send values to the client.

   Basic values are String, to send a text message, or ByteBuffer,
   to send binary message.

   Alternately, wrap the basic value in a vector; the second element
   is a response channel; the response channel will convey either :success
   or an exception; this allows the application to handle flow control.

   Options:

   :send-buffer-or-n
   : Used to create the channel, defaults to 10.
  "
  [opts]
  (let [{:keys [send-buffer-or-n]
         :or {send-buffer-or-n 10}} opts]
    (fn [^Session ws-session ^EndpointConfig _config]
      (let [send-ch (async/chan send-buffer-or-n)
            async-remote (.getAsyncRemote ws-session)]
        (go-loop []
          (if-let [payload (and (.isOpen ws-session)
                                (async/<! send-ch))]
            (let [[out-msg resp-ch] (if (sequential? payload)
                                      payload
                                      [payload nil])
                  ;; TODO: Not really async because we park for the response here.
                  result (try (async/<! (ws-send-async out-msg async-remote))
                              (catch Exception ex
                                (log/error :msg "Failed on ws-send-async"
                                           :exception ex)
                                ex))]
              (when resp-ch
                (try
                  (async/put! resp-ch result)
                  (catch Exception ex
                    (log/error :msg "Invalid response channel"
                               :exception ex))))
              (recur))
            ;; The session is closed when the channel is closed.
            (.close ws-session)))
        send-ch))))
