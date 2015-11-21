/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.ConnectionSpecSelector;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.Version;
import com.squareup.okhttp.internal.framed.FramedConnection;
import com.squareup.okhttp.internal.http.FramedTransport;
import com.squareup.okhttp.internal.http.HttpTransport;
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.internal.http.RouteException;
import com.squareup.okhttp.internal.http.Transport;
import com.squareup.okhttp.internal.tls.OkHostnameVerifier;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;

import static com.squareup.okhttp.internal.Util.closeQuietly;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;

/**
 * The sockets and streams of an HTTP, HTTPS, or HTTPS+SPDY connection. May be used for multiple
 * HTTP request/response exchanges. Connections may be direct to the origin server or via a proxy.
 *
 * <p>Typically instances of this class are created, connected and exercised automatically by the
 * HTTP client. Applications may use this class to monitor HTTP connections as members of a
 * {@linkplain ConnectionPool connection pool}.
 *
 * <p>Do not confuse this class with the misnamed {@code HttpURLConnection}, which isn't so much a
 * connection as a single request/response exchange.
 *
 * <h3>Modern TLS</h3>
 * There are tradeoffs when selecting which options to include when negotiating a secure connection
 * to a remote host. Newer TLS options are quite useful:
 * <ul>
 *     <li>Server Name Indication (SNI) enables one IP address to negotiate secure connections for
 *         multiple domain names.
 *     <li>Application Layer Protocol Negotiation (ALPN) enables the HTTPS port (443) to be used for
 *         different HTTP and SPDY protocols.
 * </ul>
 *
 * <p>Unfortunately, older HTTPS servers refuse to connect when such options are presented. Rather
 * than avoiding these options entirely, this class allows a connection to be attempted with modern
 * options and then retried without them should the attempt fail.
 *
 * <h3>Connection Sharing</h3>
 * Each connection can carry a varying number streams, depending on the underlying protocol being
 * used. HTTP/1.x connections can carry either zero or one streams. HTTP/2 connections can carry any
 * number of streams, dynamically configured with {@code SETTINGS_MAX_CONCURRENT_STREAMS}. A
 * connection currently carrying zero streams is an idle stream. We keep it alive because reusing an
 * existing connection is typically faster than establishing a new one.
 *
 * <p>When a single logical call requires multiple streams due to redirects or authorization
 * challenges, we prefer to use the same physical connection for all streams in the sequence. There
 * are potential performance and behavior consequences to this preference. To support this feature,
 * this class separates <i>allocations</i> from <i>streams</i>. An allocation is created by a call,
 * used for one or more streams, and then released. An allocated connection won't be stolen by
 * other calls while a redirect or authorization challenge is being handled.
 *
 * <p>When the maximum concurrent streams limit is reduced, some allocations will be rescinded.
 * Attempting to create new streams on these allocations will fail.
 *
 * <p>Note that an allocation may be released before its stream is completed. This is intended to
 * make bookkeeping easier for the caller: releasing the allocation as soon as the terminal stream
 * has been found. But only complete the stream once its data stream has been exhausted.
 */
public final class Connection {
  final ConnectionPool pool;
  private final Route route;

  private final List<StreamAllocationReference> allocations = new ArrayList<>();

  private Socket socket;
  private BufferedSource source;
  private BufferedSink sink;
  private Handshake handshake;
  private Protocol protocol;
  private FramedConnection framedConnection;
  private int allocationLimit = 1;
  private boolean noNewAllocations;

  /** Nanotime that this connection most recently became idle. */
  long idleAt = Long.MAX_VALUE;

  public Connection(ConnectionPool pool, Route route) {
    this.pool = pool;
    this.route = route;
  }

  void connect(int connectTimeout, int readTimeout, int writeTimeout,
      List<ConnectionSpec> connectionSpecs, boolean connectionRetryEnabled) throws RouteException {
    if (protocol != null) throw new IllegalStateException("already connected");

    RouteException routeException = null;
    ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);
    Proxy proxy = route.getProxy();
    Address address = route.getAddress();

    if (!route.address.isHttps() && !connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
      throw new RouteException(new UnknownServiceException(
          "CLEARTEXT communication not supported: " + connectionSpecs));
    }

    while (protocol == null) {
      try {
        socket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
            ? address.getSocketFactory().createSocket()
            : new Socket(proxy);
        connectSocket(connectTimeout, readTimeout, writeTimeout, connectionSpecSelector);
      } catch (IOException e) {
        Util.closeQuietly(socket);
        Util.closeQuietly(framedConnection);
        socket = null;
        source = null;
        sink = null;
        handshake = null;
        protocol = null;
        framedConnection = null;

        if (routeException == null) {
          routeException = new RouteException(e);
        } else {
          routeException.addConnectException(e);
        }

        if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
          throw routeException;
        }
      }
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  private void connectSocket(int connectTimeout, int readTimeout, int writeTimeout,
      ConnectionSpecSelector connectionSpecSelector) throws IOException {
    socket.setSoTimeout(readTimeout);
    Platform.get().connectSocket(socket, route.getSocketAddress(), connectTimeout);
    source = Okio.buffer(Okio.source(socket));
    sink = Okio.buffer(Okio.sink(socket));

    if (route.address.isHttps()) {
      connectTls(readTimeout, writeTimeout, connectionSpecSelector);
      if (!source.buffer().exhausted() || !sink.buffer().exhausted()) {
        throw new IOException("TLS tunnel left bytes behind!");
      }
      source = Okio.buffer(Okio.source(socket));
      sink = Okio.buffer(Okio.sink(socket));
    } else {
      protocol = Protocol.HTTP_1_1;
    }

    if (protocol == Protocol.SPDY_3 || protocol == Protocol.HTTP_2) {
      socket.setSoTimeout(0); // Framed connection timeouts are set per-stream.
      framedConnection = new FramedConnection.Builder(route.address.uriHost, true)
          .socket(socket, source, sink)
          .protocol(protocol)
          .build();
      framedConnection.sendConnectionPreface();
      allocationLimit = 256; // TODO(jwilson): what should this be?!
    }
  }

  private void connectTls(int readTimeout, int writeTimeout,
      ConnectionSpecSelector connectionSpecSelector) throws IOException {
    if (route.requiresTunnel()) {
      createTunnel(readTimeout, writeTimeout);
    }

    Address address = route.getAddress();
    SSLSocketFactory sslSocketFactory = address.getSslSocketFactory();
    boolean success = false;
    SSLSocket sslSocket = null;
    try {
      // Create the wrapper over the connected socket.
      sslSocket = (SSLSocket) sslSocketFactory.createSocket(
          socket, address.getUriHost(), address.getUriPort(), true /* autoClose */);

      // Configure the socket's ciphers, TLS versions, and extensions.
      ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
      if (connectionSpec.supportsTlsExtensions()) {
        Platform.get().configureTlsExtensions(
            sslSocket, address.getUriHost(), address.getProtocols());
      }

      // Force handshake. This can throw!
      sslSocket.startHandshake();
      Handshake unverifiedHandshake = Handshake.get(sslSocket.getSession());

      // Verify that the socket's certificates are acceptable for the target host.
      if (!address.getHostnameVerifier().verify(address.getUriHost(), sslSocket.getSession())) {
        X509Certificate cert = (X509Certificate) unverifiedHandshake.peerCertificates().get(0);
        throw new SSLPeerUnverifiedException("Hostname " + address.getUriHost() + " not verified:"
            + "\n    certificate: " + CertificatePinner.pin(cert)
            + "\n    DN: " + cert.getSubjectDN().getName()
            + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
      }

      // Check that the certificate pinner is satisfied by the certificates presented.
      address.getCertificatePinner().check(address.getUriHost(),
          unverifiedHandshake.peerCertificates());

      // Success! Save the handshake and the ALPN protocol.
      String maybeProtocol = connectionSpec.supportsTlsExtensions()
          ? Platform.get().getSelectedProtocol(sslSocket)
          : null;
      socket = sslSocket;
      handshake = unverifiedHandshake;
      protocol = maybeProtocol != null
          ? Protocol.get(maybeProtocol)
          : Protocol.HTTP_1_1;
      success = true;
    } catch (AssertionError e) {
      if (Util.isAndroidGetsocknameError(e)) throw new IOException(e);
      throw e;
    } finally {
      if (sslSocket != null) {
        Platform.get().afterHandshake(sslSocket);
      }
      if (!success) {
        closeQuietly(sslSocket);
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted
   * CONNECT request to create the proxy connection. This may need to be
   * retried if the proxy requires authorization.
   */
  private void createTunnel(int readTimeout, int writeTimeout) throws IOException {
    StreamAllocation allocation = reserve("TLS tunnel");

    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    if (allocation == null) throw new AssertionError(); // Failed to allocate a stream!
    HttpTransport tunnelConnection = (HttpTransport) allocation.newStream();
    if (tunnelConnection == null) throw new AssertionError(); // Connection was rescinded!

    Request tunnelRequest = createTunnelRequest();
    tunnelConnection.setTimeouts(readTimeout, writeTimeout);
    HttpUrl url = tunnelRequest.httpUrl();
    String requestLine = "CONNECT " + url.host() + ":" + url.port() + " HTTP/1.1";

    while (true) {
      tunnelConnection.writeRequest(tunnelRequest.headers(), requestLine);
      tunnelConnection.finishRequest();

      Response response = tunnelConnection.readResponseHeaders()
          .request(tunnelRequest)
          .build();

      // The response body from a CONNECT should be empty, but if it is not then we should consume
      // it before proceeding.
      long contentLength = OkHeaders.contentLength(response);
      if (contentLength == -1L) {
        contentLength = 0L;
      }
      Source body = tunnelConnection.newFixedLengthSource(contentLength);
      Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
      body.close();

      switch (response.code()) {
        case HTTP_OK:
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
          // that happens, then we will have buffered bytes that are needed by the SSLSocket!
          // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
          // that it will almost certainly fail because the proxy has sent unexpected data.
          if (allocation.stream != null) {
            throw new IOException("TLS tunnel didn't release connection!");
          }
          return;

        case HTTP_PROXY_AUTH:
          tunnelRequest = OkHeaders.processAuthHeader(
              route.getAddress().getAuthenticator(), response, route.getProxy());
          if (tunnelRequest != null) continue;
          throw new IOException("Failed to authenticate with proxy");

        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + response.code());
      }
    }
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy, or null if
   * no tunnel is necessary. Everything in the tunnel request is sent
   * unencrypted to the proxy server, so tunnels include only the minimum set of
   * headers. This avoids sending potentially sensitive data like HTTP cookies
   * to the proxy unencrypted.
   */
  private Request createTunnelRequest() throws IOException {
    HttpUrl tunnelUrl = new HttpUrl.Builder()
        .scheme("https")
        .host(route.address.uriHost)
        .port(route.address.uriPort)
        .build();
    return new Request.Builder()
        .url(tunnelUrl)
        .header("Host", Util.hostHeader(tunnelUrl))
        .header("Proxy-Connection", "Keep-Alive")
        .header("User-Agent", Version.userAgent()) // For HTTP/1.0 proxies like Squid.
        .build();
  }

  /**
   * Attempts to reserves an allocation on this connection for a call. Returns null if no
   * allocation is available.
   */
  public StreamAllocation reserve(String name) {
    synchronized (pool) {
      if (noNewAllocations || allocations.size() >= allocationLimit) return null;

      StreamAllocation result = new StreamAllocation(this);
      allocations.add(new StreamAllocationReference(result, name));
      return result;
    }
  }

  /**
   * Release the reservation on {@code streamAllocation}. If a stream is currently active it may
   * continue to use this connection until it is complete.
   */
  public void release(StreamAllocation streamAllocation) {
    synchronized (pool) {
      if (streamAllocation.released) throw new IllegalStateException("already released");

      streamAllocation.released = true;
      if (streamAllocation.stream == null) {
        remove(streamAllocation);
      }
    }
  }

  void remove(StreamAllocation streamAllocation) {
    for (int i = 0, size = allocations.size(); i < size; i++) {
      StreamAllocationReference weakReference = allocations.get(i);
      if (weakReference.get() == streamAllocation) {
        allocations.remove(i);

        if (allocations.isEmpty()) {
          idleAt = System.nanoTime();
          // TODO(jwilson): schedule a cleanup thread if allocationLimit == 0.
        }

        return;
      }
    }
    throw new IllegalArgumentException("unexpected allocation: " + streamAllocation);
  }

  /** Test for a stale socket. */
  public boolean isReadable() {
    try {
      int readTimeout = socket.getSoTimeout();
      try {
        socket.setSoTimeout(1);
        if (source.exhausted()) {
          return false; // Stream is exhausted; socket is closed.
        }
        return true;
      } finally {
        socket.setSoTimeout(readTimeout);
      }
    } catch (SocketTimeoutException ignored) {
      return true; // Read timed out; socket is good.
    } catch (IOException e) {
      return false; // Couldn't read; socket is closed.
    }
  }

  /**
   * Prevents new streams from being created on this connection. This is similar to setting the
   * allocation limit to 0, except that this call is permanent.
   */
  public void noNewStreams() {
    synchronized (pool) {
      noNewAllocations = true;
      for (int i = 0; i < allocations.size(); i++) {
        allocations.get(i).rescind();
      }
    }
  }

  /**
   * Sets the maximum number of streams that this connection will carry. Existing streams will not
   * be interrupted, but existing allocations may be prevented from creating new streams.
   */
  public void setAllocationLimit(int allocationLimit) {
    synchronized (pool) {
      if (allocationLimit < 0) throw new IllegalArgumentException();
      this.allocationLimit = allocationLimit;
      for (int i = allocationLimit; i < allocations.size(); i++) {
        allocations.get(i).rescind();
      }
    }
  }

  /**
   * Look through the allocations held by this connection and confirm that they're all still
   * alive. If they aren't, we have a leaked allocation. In which case we prevent this connection
   * from taking new allocations so that it may gracefully shut down.
   */
  public void pruneLeakedAllocations() {
    synchronized (pool) {
      for (Iterator<StreamAllocationReference> i = allocations.iterator(); i.hasNext(); ) {
        StreamAllocationReference reference = i.next();
        if (reference.get() == null) {
          Internal.logger.warning("Call " + reference.name
              + " leaked a connection. Did you forget to close a response body?");
          noNewAllocations = true;
          i.remove();
          if (allocations.isEmpty()) {
            idleAt = System.nanoTime();
            // TODO(jwilson): schedule a cleanup thread if allocationLimit == 0.
          }
        }
      }
    }
  }

  boolean isMultiplexed() {
    return framedConnection != null;
  }

  /** Returns the number of allocations currently held. */
  int size() {
    synchronized (pool) {
      return allocations.size();
    }
  }

  /** Returns the route used by this connection. */
  public Route getRoute() {
    return route;
  }

  /**
   * Returns the socket that this connection uses, or null if the connection
   * is not currently connected.
   */
  public Socket getSocket() {
    return socket;
  }

  public Handshake getHandshake() {
    return handshake;
  }

  /**
   * Returns the protocol negotiated by this connection, or {@link Protocol#HTTP_1_1} if no protocol
   * has been negotiated. This method returns {@link Protocol#HTTP_1_1} even if the remote peer is
   * using {@link Protocol#HTTP_1_0}.
   */
  public Protocol getProtocol() {
    return protocol != null ? protocol : Protocol.HTTP_1_1;
  }

  @Override public String toString() {
    return "Connection{" + route.address.uriHost + ":" + route.address.uriPort + ","
        + " proxy=" + route.proxy
        + " hostAddress=" + route.inetSocketAddress.getAddress().getHostAddress()
        + " cipherSuite=" + (handshake != null ? handshake.cipherSuite() : "none")
        + " protocol=" + protocol
        + '}';
  }

  public Transport newTransport(StreamAllocation streamAllocation) {
    if (framedConnection != null) {
      // TODO(jwilson): release streams.
      return new FramedTransport(streamAllocation, framedConnection);
    } else {
      return new HttpTransport(streamAllocation, source, sink);
    }
  }

  public void disconnect() {
    // TODO(jwilson)
    Util.closeQuietly(socket);
  }

  public void close() throws IOException {
    socket.close();
  }

  public BufferedSource rawSource() {
    return source;
  }

  public BufferedSink rawSink() {
    return sink;
  }

  private static final class StreamAllocationReference extends WeakReference<StreamAllocation> {
    private final String name;

    public StreamAllocationReference(StreamAllocation streamAllocation, String name) {
      super(streamAllocation);
      this.name = name;
    }

    public void rescind() {
      StreamAllocation streamAllocation = get();
      if (streamAllocation != null) {
        streamAllocation.rescinded = true;
      }
    }
  }
}
