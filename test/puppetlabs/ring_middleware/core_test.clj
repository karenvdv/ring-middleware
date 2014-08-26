(ns puppetlabs.ring-middleware.core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.ring-middleware.common :refer :all]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.ring-middleware.core :refer [wrap-proxy]]
            [puppetlabs.http.client.sync :as http-client]))

(defn post-target-handler
  [req]
  (if (= (:request-method req) :post)
    {:status 200 :body (slurp (:body req))}
    {:status 404 :body "D'oh"}))

(defn proxy-target-handler
  [req]
  (condp = (:uri req)
    "/hello/world" {:status 200 :body (str "Hello, World!")}
    {:status 404 :body "D'oh"}))

(defn proxy-error-handler
  [req]
  {:status 404 :body "D'oh"})

(def proxy-wrapped-app
  (-> proxy-error-handler
      (wrap-proxy "/hello-proxy" "http://localhost:9000/hello")))

(defmacro with-target-and-proxy-servers
  [{:keys [target proxy proxy-handler]} & body]
  `(with-app-with-config proxy-target-app#
     [jetty9-service]
     {:webserver ~target}
     (let [target-webserver# (get-service proxy-target-app# :WebserverService)]
       (add-ring-handler
         target-webserver#
         proxy-target-handler
         "/hello")
       (add-ring-handler
         target-webserver#
         post-target-handler
         "/hello/post/"))
       (with-app-with-config proxy-app#
         [jetty9-service]
         {:webserver ~proxy}
         (let [proxy-webserver# (get-service proxy-app# :WebserverService)]
           (add-ring-handler proxy-webserver# ~proxy-handler "/hello-proxy"))
         ~@body)))

(deftest test-proxy-servlet
  (testing "basic proxy support"
    (with-target-and-proxy-servers
      {:target        {:host "0.0.0.0"
                       :port 9000}
       :proxy         {:host "0.0.0.0"
                       :port 10000}
       :proxy-handler proxy-wrapped-app}
      (let [response (http-client/get "http://localhost:9000/hello/world" {:as :text})]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello, World!")))
      (let [response (http-client/get "http://localhost:10000/hello-proxy/world" {:as :text})]
        (is (= (:status response) 200))
        (is (= (:body response) "Hello, World!")))
      (let [response (http-client/get "http://localhost:10000/hello-proxy/world" {:as :stream})]
        (is (= (slurp (:body response)) "Hello, World!")))
      (let [response (http-client/post "http://localhost:10000/hello-proxy/post/" {:as :stream :body "I'm posted!"})]
        (is (= (:status response) 200))
        (is (= (slurp (:body response)) "I'm posted!"))))))
