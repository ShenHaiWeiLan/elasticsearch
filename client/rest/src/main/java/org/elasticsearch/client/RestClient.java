/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.nio.client.methods.HttpAsyncMethods;
import org.apache.http.nio.protocol.HttpAsyncRequestProducer;
import org.apache.http.nio.protocol.HttpAsyncResponseConsumer;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client that connects to an elasticsearch cluster through http.
 * Must be created using {@link RestClientBuilder}, which allows to set all the different options or just rely on defaults.
 * The hosts that are part of the cluster need to be provided at creation time, but can also be replaced later
 * by calling {@link #setHosts(HttpHost...)}.
 * The method {@link #performRequest(String, String, Map, HttpEntity, Header...)} allows to send a request to the cluster. When
 * sending a request, a host gets selected out of the provided ones in a round-robin fashion. Failing hosts are marked dead and
 * retried after a certain amount of time (minimum 1 minute, maximum 30 minutes), depending on how many times they previously
 * failed (the more failures, the later they will be retried). In case of failures all of the alive nodes (or dead nodes that
 * deserve a retry) are retried till one responds or none of them does, in which case an {@link IOException} will be thrown.
 *
 * Requests can be traced by enabling trace logging for "tracer". The trace logger outputs requests and responses in curl format.
 */
public final class RestClient implements Closeable {

    private static final Log logger = LogFactory.getLog(RestClient.class);
    public static ContentType JSON_CONTENT_TYPE = ContentType.create("application/json", Consts.UTF_8);

    private final CloseableHttpAsyncClient client;
    //we don't rely on default headers supported by HttpAsyncClient as those cannot be replaced
    private final Header[] defaultHeaders;
    private final long maxRetryTimeoutMillis;
    private final AtomicInteger lastHostIndex = new AtomicInteger(0);
    private volatile Set<HttpHost> hosts;
    private final ConcurrentMap<HttpHost, DeadHostState> blacklist = new ConcurrentHashMap<>();
    private final FailureListener failureListener;

    RestClient(CloseableHttpAsyncClient client, long maxRetryTimeoutMillis, Header[] defaultHeaders,
                       HttpHost[] hosts, FailureListener failureListener) {
        this.client = client;
        this.maxRetryTimeoutMillis = maxRetryTimeoutMillis;
        this.defaultHeaders = defaultHeaders;
        this.failureListener = failureListener;
        setHosts(hosts);
    }

    /**
     * Replaces the hosts that the client communicates with.
     * @see HttpHost
     */
    public synchronized void setHosts(HttpHost... hosts) {
        if (hosts == null || hosts.length == 0) {
            throw new IllegalArgumentException("hosts must not be null nor empty");
        }
        Set<HttpHost> httpHosts = new HashSet<>();
        for (HttpHost host : hosts) {
            Objects.requireNonNull(host, "host cannot be null");
            httpHosts.add(host);
        }
        this.hosts = Collections.unmodifiableSet(httpHosts);
        this.blacklist.clear();
    }

    /**
     * Sends a request to the elasticsearch cluster that the current client points to.
     * Shortcut to {@link #performRequest(String, String, Map, HttpEntity, Header...)} but without parameters and request body.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param headers the optional request headers
     * @return the response returned by elasticsearch
     * @throws IOException in case of a problem or the connection was aborted
     * @throws ClientProtocolException in case of an http protocol error
     * @throws ResponseException in case elasticsearch responded with a status code that indicated an error
     */
    public Response performRequest(String method, String endpoint, Header... headers) throws Exception {
        return performRequest(method, endpoint, Collections.<String, String>emptyMap(), (HttpEntity)null, headers);
    }

    /**
     * Sends a request to the elasticsearch cluster that the current client points to.
     * Shortcut to {@link #performRequest(String, String, Map, HttpEntity, Header...)} but without request body.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param params the query_string parameters
     * @param headers the optional request headers
     * @return the response returned by elasticsearch
     * @throws IOException in case of a problem or the connection was aborted
     * @throws ClientProtocolException in case of an http protocol error
     * @throws ResponseException in case elasticsearch responded with a status code that indicated an error
     */
    public Response performRequest(String method, String endpoint, Map<String, String> params, Header... headers) throws Exception {
        return performRequest(method, endpoint, params, (HttpEntity)null, headers);
    }

    /**
     * Sends a request to the elasticsearch cluster that the current client points to. Blocks till the request is completed and returns
     * its response of fails by throwing an exception. Selects a host out of the provided ones in a round-robin fashion. Failing hosts
     * are marked dead and retried after a certain amount of time (minimum 1 minute, maximum 30 minutes), depending on how many times
     * they previously failed (the more failures, the later they will be retried). In case of failures all of the alive nodes (or dead
     * nodes that deserve a retry) are retried till one responds or none of them does, in which case an {@link IOException} will be thrown.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param params the query_string parameters
     * @param entity the body of the request, null if not applicable
     * @param headers the optional request headers
     * @return the response returned by elasticsearch
     * @throws IOException in case of a problem or the connection was aborted
     * @throws ClientProtocolException in case of an http protocol error
     * @throws ResponseException in case elasticsearch responded with a status code that indicated an error
     */
    public Response performRequest(String method, String endpoint, Map<String, String> params,
                                   HttpEntity entity, Header... headers) throws Exception {
        HttpAsyncResponseConsumer<HttpResponse> consumer = HttpAsyncMethods.createConsumer();
        SyncResponseListener listener = new SyncResponseListener();
        performRequest(method, endpoint, params, entity, consumer, listener, headers);
        return listener.get();
    }

    /**
     * Sends a request to the elasticsearch cluster that the current client points to.
     * Shortcut to {@link #performRequest(String, String, Map, HttpEntity, HttpAsyncResponseConsumer, ResponseListener, Header...)}
     * but without parameters, request body and async response consumer. A default response consumer, specifically an instance of
     * ({@link org.apache.http.nio.protocol.BasicAsyncResponseConsumer} will be created and used.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param responseListener the {@link ResponseListener} to notify when the request is completed or fails
     * @param headers the optional request headers
     */
    public void performRequest(String method, String endpoint, ResponseListener responseListener, Header... headers) {
        performRequest(method, endpoint, Collections.<String, String>emptyMap(), null, responseListener, headers);
    }

    /**
     * Sends a request to the elasticsearch cluster that the current client points to.
     * Shortcut to {@link #performRequest(String, String, Map, HttpEntity, HttpAsyncResponseConsumer, ResponseListener, Header...)}
     * but without request body and async response consumer. A default response consumer, specifically an instance of
     * ({@link org.apache.http.nio.protocol.BasicAsyncResponseConsumer} will be created and used.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param params the query_string parameters
     * @param responseListener the {@link ResponseListener} to notify when the request is completed or fails
     * @param headers the optional request headers
     */
    public void performRequest(String method, String endpoint, Map<String, String> params,
                               ResponseListener responseListener, Header... headers) {
        performRequest(method, endpoint, params, null, responseListener, headers);
    }

    /**
     /**
     * Sends a request to the elasticsearch cluster that the current client points to.
     * Shortcut to {@link #performRequest(String, String, Map, HttpEntity, HttpAsyncResponseConsumer, ResponseListener, Header...)}
     * but without an async response consumer, meaning that a {@link org.apache.http.nio.protocol.BasicAsyncResponseConsumer}
     * will be created and used.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param params the query_string parameters
     * @param entity the body of the request, null if not applicable
     * @param responseListener the {@link ResponseListener} to notify when the request is completed or fails
     * @param headers the optional request headers
     */
    public void performRequest(String method, String endpoint, Map<String, String> params,
                               HttpEntity entity, ResponseListener responseListener, Header... headers) {
        HttpAsyncResponseConsumer<HttpResponse> responseConsumer = HttpAsyncMethods.createConsumer();
        performRequest(method, endpoint, params, entity, responseConsumer, responseListener, headers);
    }

    /**
     * Sends a request to the elasticsearch cluster that the current client points to. The request is executed asynchronously
     * and the provided {@link ResponseListener} gets notified whenever it is completed or it fails.
     * Selects a host out of the provided ones in a round-robin fashion. Failing hosts are marked dead and retried after a certain
     * amount of time (minimum 1 minute, maximum 30 minutes), depending on how many times they previously failed (the more failures,
     * the later they will be retried). In case of failures all of the alive nodes (or dead nodes that deserve a retry) are retried
     * till one responds or none of them does, in which case an {@link IOException} will be thrown.
     *
     * @param method the http method
     * @param endpoint the path of the request (without host and port)
     * @param params the query_string parameters
     * @param entity the body of the request, null if not applicable
     * @param responseConsumer the {@link HttpAsyncResponseConsumer} callback
     * @param responseListener the {@link ResponseListener} to notify when the request is completed or fails
     * @param headers the optional request headers
     */
    public void performRequest(String method, String endpoint, Map<String, String> params,
                               HttpEntity entity, HttpAsyncResponseConsumer<HttpResponse> responseConsumer,
                               ResponseListener responseListener, Header... headers) {
        URI uri = buildUri(endpoint, params);
        HttpRequestBase request = createHttpRequest(method, uri, entity);
        setHeaders(request, headers);
        FailureTrackingListener failureTrackingListener = new FailureTrackingListener(responseListener);
        long startTime = System.nanoTime();
        performRequest(startTime, nextHost().iterator(), request, responseConsumer, failureTrackingListener);
    }

    private void performRequest(final long startTime, final Iterator<HttpHost> hosts, final HttpRequestBase request,
                                final HttpAsyncResponseConsumer<HttpResponse> responseConsumer,
                                final FailureTrackingListener listener) {
        final HttpHost host = hosts.next();
        //we stream the request body if the entity allows for it
        HttpAsyncRequestProducer requestProducer = HttpAsyncMethods.create(host, request);
        client.execute(requestProducer, responseConsumer, new FutureCallback<HttpResponse>() {
            @Override
            public void completed(HttpResponse httpResponse) {
                try {
                    RequestLogger.logResponse(logger, request, host, httpResponse);
                    int statusCode = httpResponse.getStatusLine().getStatusCode();
                    Response response = new Response(request.getRequestLine(), host, httpResponse);
                    if (isSuccessfulResponse(request.getMethod(), statusCode)) {
                        onResponse(host);
                        listener.onSuccess(response);
                    } else {
                        ResponseException responseException = new ResponseException(response);
                        if (mustRetry(statusCode)) {
                            //mark host dead and retry against next one
                            onFailure(host);
                            retryIfPossible(responseException, hosts, request);
                        } else {
                            //mark host alive and don't retry, as the error should be a request problem
                            onResponse(host);
                            listener.onDefinitiveFailure(responseException);
                        }
                    }
                } catch(Exception e) {
                    listener.onDefinitiveFailure(e);
                }
            }

            @Override
            public void failed(Exception failure) {
                try {
                    RequestLogger.logFailedRequest(logger, request, host, failure);
                    onFailure(host);
                    retryIfPossible(failure, hosts, request);
                } catch(Exception e) {
                    listener.onDefinitiveFailure(e);
                }
            }

            private void retryIfPossible(Exception exception, Iterator<HttpHost> hosts, HttpRequestBase request) {
                if (hosts.hasNext()) {
                    //in case we are retrying, check whether maxRetryTimeout has been reached
                    long timeElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
                    long timeout = maxRetryTimeoutMillis - timeElapsedMillis;
                    if (timeout <= 0) {
                        IOException retryTimeoutException = new IOException(
                                "request retries exceeded max retry timeout [" + maxRetryTimeoutMillis + "]");
                        listener.onDefinitiveFailure(retryTimeoutException);
                    } else {
                        listener.trackFailure(exception);
                        request.reset();
                        performRequest(startTime, hosts, request, responseConsumer, listener);
                    }
                } else {
                    listener.onDefinitiveFailure(exception);
                }
            }

            @Override
            public void cancelled() {
            }
        });
    }

    private void setHeaders(HttpRequest httpRequest, Header[] requestHeaders) {
        Objects.requireNonNull(requestHeaders, "request headers must not be null");
        for (Header defaultHeader : defaultHeaders) {
            httpRequest.setHeader(defaultHeader);
        }
        for (Header requestHeader : requestHeaders) {
            Objects.requireNonNull(requestHeader, "request header must not be null");
            httpRequest.setHeader(requestHeader);
        }
    }

    /**
     * Returns an iterator of hosts to be used for a request call.
     * Ideally, the first host is retrieved from the iterator and used successfully for the request.
     * Otherwise, after each failure the next host should be retrieved from the iterator so that the request can be retried till
     * the iterator is exhausted. The maximum total of attempts is equal to the number of hosts that are available in the iterator.
     * The iterator returned will never be empty, rather an {@link IllegalStateException} in case there are no hosts.
     * In case there are no healthy hosts available, or dead ones to be be retried, one dead host gets returned.
     */
    private Iterable<HttpHost> nextHost() {
        Set<HttpHost> filteredHosts = new HashSet<>(hosts);
        for (Map.Entry<HttpHost, DeadHostState> entry : blacklist.entrySet()) {
            if (System.nanoTime() - entry.getValue().getDeadUntilNanos() < 0) {
                filteredHosts.remove(entry.getKey());
            }
        }

        if (filteredHosts.isEmpty()) {
            //last resort: if there are no good hosts to use, return a single dead one, the one that's closest to being retried
            List<Map.Entry<HttpHost, DeadHostState>> sortedHosts = new ArrayList<>(blacklist.entrySet());
            Collections.sort(sortedHosts, new Comparator<Map.Entry<HttpHost, DeadHostState>>() {
                @Override
                public int compare(Map.Entry<HttpHost, DeadHostState> o1, Map.Entry<HttpHost, DeadHostState> o2) {
                    return Long.compare(o1.getValue().getDeadUntilNanos(), o2.getValue().getDeadUntilNanos());
                }
            });
            HttpHost deadHost = sortedHosts.get(0).getKey();
            logger.trace("resurrecting host [" + deadHost + "]");
            return Collections.singleton(deadHost);
        }

        List<HttpHost> rotatedHosts = new ArrayList<>(filteredHosts);
        Collections.rotate(rotatedHosts, rotatedHosts.size() - lastHostIndex.getAndIncrement());
        return rotatedHosts;
    }

    /**
     * Called after each successful request call.
     * Receives as an argument the host that was used for the successful request.
     */
    private void onResponse(HttpHost host) {
        DeadHostState removedHost = this.blacklist.remove(host);
        if (logger.isDebugEnabled() && removedHost != null) {
            logger.debug("removed host [" + host + "] from blacklist");
        }
    }

    /**
     * Called after each failed attempt.
     * Receives as an argument the host that was used for the failed attempt.
     */
    private void onFailure(HttpHost host) throws IOException {
        while(true) {
            DeadHostState previousDeadHostState = blacklist.putIfAbsent(host, DeadHostState.INITIAL_DEAD_STATE);
            if (previousDeadHostState == null) {
                logger.debug("added host [" + host + "] to blacklist");
                break;
            }
            if (blacklist.replace(host, previousDeadHostState, new DeadHostState(previousDeadHostState))) {
                logger.debug("updated host [" + host + "] already in blacklist");
                break;
            }
        }
        failureListener.onFailure(host);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    private static boolean isSuccessfulResponse(String method, int statusCode) {
        return statusCode < 300 || (HttpHead.METHOD_NAME.equals(method) && statusCode == 404);
    }

    private static boolean mustRetry(int statusCode) {
        switch(statusCode) {
            case 502:
            case 503:
            case 504:
                return true;
        }
        return false;
    }

    private static Exception addSuppressedException(Exception suppressedException, Exception currentException) {
        if (suppressedException != null) {
            currentException.addSuppressed(suppressedException);
        }
        return currentException;
    }

    private static HttpRequestBase createHttpRequest(String method, URI uri, HttpEntity entity) {
        switch(method.toUpperCase(Locale.ROOT)) {
            case HttpDeleteWithEntity.METHOD_NAME:
                return addRequestBody(new HttpDeleteWithEntity(uri), entity);
            case HttpGetWithEntity.METHOD_NAME:
                return addRequestBody(new HttpGetWithEntity(uri), entity);
            case HttpHead.METHOD_NAME:
                return addRequestBody(new HttpHead(uri), entity);
            case HttpOptions.METHOD_NAME:
                return addRequestBody(new HttpOptions(uri), entity);
            case HttpPatch.METHOD_NAME:
                return addRequestBody(new HttpPatch(uri), entity);
            case HttpPost.METHOD_NAME:
                HttpPost httpPost = new HttpPost(uri);
                addRequestBody(httpPost, entity);
                return httpPost;
            case HttpPut.METHOD_NAME:
                return addRequestBody(new HttpPut(uri), entity);
            case HttpTrace.METHOD_NAME:
                return addRequestBody(new HttpTrace(uri), entity);
            default:
                throw new UnsupportedOperationException("http method not supported: " + method);
        }
    }

    private static HttpRequestBase addRequestBody(HttpRequestBase httpRequest, HttpEntity entity) {
        if (entity != null) {
            if (httpRequest instanceof HttpEntityEnclosingRequestBase) {
                ((HttpEntityEnclosingRequestBase)httpRequest).setEntity(entity);
            } else {
                throw new UnsupportedOperationException(httpRequest.getMethod() + " with body is not supported");
            }
        }
        return httpRequest;
    }

    private static URI buildUri(String path, Map<String, String> params) {
        Objects.requireNonNull(params, "params must not be null");
        try {
            URIBuilder uriBuilder = new URIBuilder(path);
            for (Map.Entry<String, String> param : params.entrySet()) {
                uriBuilder.addParameter(param.getKey(), param.getValue());
            }
            return uriBuilder.build();
        } catch(URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static class FailureTrackingListener {
        private final ResponseListener responseListener;
        private volatile Exception exception;

        FailureTrackingListener(ResponseListener responseListener) {
            this.responseListener = responseListener;
        }

        void onSuccess(Response response) {
            responseListener.onSuccess(response);
        }

        void onDefinitiveFailure(Exception exception) {
            trackFailure(exception);
            responseListener.onFailure(this.exception);
        }

        void trackFailure(Exception exception) {
            this.exception = addSuppressedException(this.exception, exception);
        }
    }

    private static class SyncResponseListener implements ResponseListener {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Response response;
        volatile Exception exception;

        @Override
        public void onSuccess(Response response) {
            this.response = response;
            latch.countDown();
        }

        @Override
        public void onFailure(Exception exception) {
            this.exception = exception;
            latch.countDown();
        }

        Response get() throws Exception {
            latch.await();
            if (response != null) {
                assert exception == null;
                return response;
            }
            assert exception != null;
            throw exception;
        }
    }

    /**
     * Returns a new {@link RestClientBuilder} to help with {@link RestClient} creation.
     */
    public static RestClientBuilder builder(HttpHost... hosts) {
        return new RestClientBuilder(hosts);
    }

    /**
     * Listener that allows to be notified whenever a failure happens. Useful when sniffing is enabled, so that we can sniff on failure.
     * The default implementation is a no-op.
     */
    public static class FailureListener {
        /**
         * Notifies that the host provided as argument has just failed
         */
        public void onFailure(HttpHost host) {

        }
    }
}
