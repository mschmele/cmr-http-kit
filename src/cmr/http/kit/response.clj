(ns cmr.http.kit.response
  "This namespace defines a default set of transform functions suitable for use
  in presenting results to HTTP clients.

  Note that ring-based middleeware may take advantage of these functions either
  by single use or composition."
  (:require
   [cheshire.core :as json]
   [clojure.data.xml :as xml]
   [clojure.string :as string]
   [ring.util.http-response :as response]
   [taoensso.timbre :as log]
   [xml-in.core :as xml-in])
  (:refer-clojure :exclude [error-handler]))

(defn stream->str
  [body]
  (if (string? body)
    body
    (slurp body)))

(defn parse-json-body
  [body]
  (let [str-data (stream->str body)
        json-data (json/parse-string str-data true)]
    (log/trace "str-data:" str-data)
    (log/trace "json-data:" json-data)
    json-data))

(defn json-errors
  [body]
  (:errors (parse-json-body body)))

(defn parse-xml-body
  [body]
  (let [str-data (if (string? body) body (slurp body))
        xml-data (xml/parse-str str-data)]
    (log/trace "str-data:" str-data)
    (log/trace "xml-data:" xml-data)
    xml-data))

(defn xml-errors
  [body]
  (vec (xml-in/find-all (parse-xml-body body)
                        [:errors :error])))
(defn ok
  [_request & args]
  (response/ok args))

(defn not-found
  [_request]
  (response/content-type
   (response/not-found "Not Found")
   "text/html"))

(defn cors
  [request response]
  (case (:request-method request)
    :options (-> response
                 (response/content-type "text/plain; charset=utf-8")
                 (response/header "Access-Control-Allow-Origin" "*")
                 (response/header "Access-Control-Allow-Methods" "POST, PUT, GET, DELETE, OPTIONS")
                 (response/header "Access-Control-Allow-Headers" "Content-Type")
                 (response/header "Access-Control-Max-Age" "2592000"))
    (response/header response "Access-Control-Allow-Origin" "*")))

(defn add-header
  [response field value]
  (assoc-in response [:headers (if (string? field) field (name field))] value))

(defn version-media-type
  [response value]
  (add-header response :cmr-media-type value))

(defn errors
  [errors]
  {:errors errors})

(defn error
  [error]
  (errors [error]))

(defn not-allowed
  ([message]
   (not-allowed message []))
  ([message other-errors]
   (-> (conj other-errors message)
       errors
       json/generate-string
       response/forbidden
       (response/content-type "application/json"))))

(defn error-handler
  ([status headers body]
    (error-handler
      status
      headers
      body
      (format "Unexpected cmr-authz error (%s)." status)))
  ([status headers body default-msg]
    (let [ct (:content-type headers)]
      (log/trace "Headers:" headers)
      (log/trace "Content-Type:" ct)
      (log/trace "Body:" body)
      (cond (nil? ct)
            (do
              (log/error body)
              {:errors [body]})

            (string/starts-with? ct "application/xml")
            (let [errs (xml-errors body)]
              (log/error errs)
              {:errors errs})

            (string/starts-with? ct "application/json")
            (let [errs (json-errors body)]
              (log/error errs)
              {:errors errs})

            :else
            {:errors [default-msg]}))))

(defn client-handler
  ([response]
    (client-handler response error-handler))
  ([response err-handler]
    (client-handler response err-handler identity))
  ([{:keys [status headers body error]} err-handler parse-fn]
    (log/debug "Handling client response ...")
    (cond error
          (do
            (log/error error)
            {:errors [error]})

          (>= status 400)
          (err-handler status headers body)

          :else
          (-> body
              stream->str
              ((fn [x] (log/trace "headers:" headers)
                       (log/trace "body:" x) x))
              parse-fn))))

(def json-handler #(client-handler % error-handler parse-json-body))
(def xml-handler #(client-handler % error-handler parse-xml-body))