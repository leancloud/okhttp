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

import com.squareup.okhttp.internal.Util;
import com.sun.xml.internal.bind.v2.TODO;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
  public synchronized Connection get(Address address) {
    return null;
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
