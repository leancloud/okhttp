/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.RouteDatabase;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.RouteException;
import com.squareup.okhttp.internal.http.RouteSelector;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Manages open HTTP and SPDY connections. Requests that share the same {@link Address} may share a
 * connection. This class implements the policy of which idle connections to keep open for future
 * use.
 *
 * <p>The {@link #getDefault() system-wide default} uses system properties for tuning parameters:
 * <ul>
 *     <li>{@code http.keepAlive} true if HTTP and SPDY connections should be reused at all. Default
 *         is true.
 *     <li>{@code http.maxConnections} maximum number of idle connections to each to keep in the
 *         pool. Default is 5.
 *     <li>{@code http.keepAliveDuration} Time in milliseconds to keep the connection alive in the
 *         pool before closing it. Default is 5 minutes.
 * </ul>
 *
 * <p>The default instance <i>doesn't</i> adjust its configuration as system properties are changed.
 * This assumes that the applications that set these parameters do so before making HTTP
 * connections, and that this class is initialized lazily.
 */
public final class ConnectionPool {
  private static final long DEFAULT_KEEP_ALIVE_DURATION_MS = 5 * 60 * 1000; // 5 min

  private static final ConnectionPool systemDefault;

  static {
    String keepAlive = System.getProperty("http.keepAlive");
    String keepAliveDuration = System.getProperty("http.keepAliveDuration");
    String maxIdleConnections = System.getProperty("http.maxConnections");
    long keepAliveDurationMs = keepAliveDuration != null
        ? Long.parseLong(keepAliveDuration)
        : DEFAULT_KEEP_ALIVE_DURATION_MS;
    if (keepAlive != null && !Boolean.parseBoolean(keepAlive)) {
      systemDefault = new ConnectionPool(0, keepAliveDurationMs);
    } else if (maxIdleConnections != null) {
      systemDefault = new ConnectionPool(Integer.parseInt(maxIdleConnections), keepAliveDurationMs);
    } else {
      systemDefault = new ConnectionPool(5, keepAliveDurationMs);
    }
  }

  private final RouteDatabase routeDatabase = new RouteDatabase();

  /** The maximum number of idle connections. */
  private final int maxIdleConnections;
  private final long keepAliveDurationNs;

  private final List<Connection> connections = new ArrayList<>();

  private Executor executor = new ThreadPoolExecutor(
      0 /* corePoolSize */, 1 /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new LinkedBlockingQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

  public ConnectionPool(int maxIdleConnections, long keepAliveDurationMs) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = TimeUnit.MILLISECONDS.toNanos(keepAliveDurationMs);
  }

  public static ConnectionPool getDefault() {
    return systemDefault;
  }

  /** Returns total number of connections in the pool. */
  public synchronized int getConnectionCount() {
    return connections.size();
  }

  /** @deprecated Use {@link #getMultiplexedConnectionCount()}. */
  @Deprecated
  public synchronized int getSpdyConnectionCount() {
    return getMultiplexedConnectionCount();
  }

  /** Returns total number of multiplexed connections in the pool. */
  public synchronized int getMultiplexedConnectionCount() {
    int total = 0;
    for (Connection connection : connections) {
      if (connection.isMultiplexed()) total++;
    }
    return total;
  }

  /** Returns total number of http connections in the pool. */
  public synchronized int getHttpConnectionCount() {
    return connections.size() - getMultiplexedConnectionCount();
  }

  /** Returns a connection to {@code address}, or null if no such connection exists. */
  public StreamAllocation get(Address address, int connectTimeout, int readTimeout,
      int writeTimeout, boolean connectionRetryEnabled) throws IOException {
    // Always prefer pooled connections over new connections.
    // TODO(jwilson): pool.
    //for (Connection pooled; (pooled = pool.get(address)) != null; ) {
    //  if (networkRequest.method().equals("GET") || Internal.instance.isReadable(pooled)) {
    //    return pooled;
    //  }
    //  closeQuietly(pooled.getSocket());
    //}

    RouteSelector routeSelector = new RouteSelector(address, routeDatabase);
    List<ConnectionSpec> connectionSpecs = address.getConnectionSpecs();

    while (true) {
      Route route = routeSelector.next();
      Connection connection = new Connection(this, route);
      try {
        connection.connect(connectTimeout, readTimeout, writeTimeout, connectionSpecs,
            connectionRetryEnabled);
        routeDatabase.connected(route);

        // TODO(jwilson) set timeouts.
        return connection.reserve("");
      } catch (RouteException e) {
        if (!recover(routeSelector, connection, e, connectionRetryEnabled)) {
          throw e.getLastConnectException();
        }
      }
    }
  }

  /**
   * Attempt to recover from failure to connect via a route. Returns true if recovery was successful
   * and another attempt should be made.
   */
  public boolean recover(RouteSelector routeSelector, Connection connection, RouteException e,
      boolean connectionRetryEnabled) throws IOException {
    if (routeSelector != null && connection != null) {
      routeSelector.connectFailed(connection.getRoute(), e.getLastConnectException());
    }

    if (routeSelector == null && connection == null // No connection.
        || routeSelector != null && !routeSelector.hasNext() // No more routes to attempt.
        || !isRecoverable(e, connectionRetryEnabled)) {
      return false;
    }

    return true;
  }

  private boolean isRecoverable(RouteException e, boolean connectionRetryEnabled) {
    // If the application has opted-out of recovery, don't recover.
    if (!connectionRetryEnabled) {
      return false;
    }

    // Problems with a route may mean the connection can be retried with a new route, or may
    // indicate a client-side or server-side issue that should not be retried. To tell, we must look
    // at the cause.

    IOException ioe = e.getLastConnectException();

    // If there was a protocol problem, don't recover.
    if (ioe instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout
    // we should try the next route (if there is one).
    if (ioe instanceof InterruptedIOException) {
      return ioe instanceof SocketTimeoutException;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    if (ioe instanceof SSLHandshakeException) {
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (ioe.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (ioe instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  /** Close and remove all idle connections in the pool. */
  public void evictAll() {
    synchronized (this) {
      for (Connection connection : connections) {
        connection.noNewStreams();
      }
    }
  }
}
