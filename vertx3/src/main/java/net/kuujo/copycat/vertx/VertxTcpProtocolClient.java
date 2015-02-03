/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.vertx;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import net.kuujo.copycat.protocol.ProtocolClient;
import net.kuujo.copycat.protocol.ProtocolException;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Vert.x TCP protocol client.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class VertxTcpProtocolClient implements ProtocolClient {
  private Vertx vertx = Vertx.vertx();
  private final String host;
  private final int port;
  private final VertxTcpProtocol protocol;
  private NetClient client;
  private NetSocket socket;
  private final Map<Object, ResponseHolder> responses = new HashMap<>(1000);
  private long requestId;

  /**
   * Holder for response handlers.
   */
  private static class ResponseHolder {
    private final CompletableFuture<ByteBuffer> future;
    private final long timer;
    private ResponseHolder(long timerId, CompletableFuture<ByteBuffer> future) {
      this.timer = timerId;
      this.future = future;
    }
  }

  public VertxTcpProtocolClient(String host, int port, VertxTcpProtocol protocol) {
    this.host = host;
    this.port = port;
    this.protocol = protocol;
  }

  @Override
  public CompletableFuture<ByteBuffer> write(ByteBuffer request) {
    CompletableFuture<ByteBuffer> future = new CompletableFuture<>();
    if (socket != null) {
      long requestId = this.requestId++;
      byte[] bytes = new byte[request.remaining()];
      request.get(bytes);
      socket.write(Buffer.buffer().appendInt(bytes.length).appendLong(requestId).appendBytes(bytes));
      storeFuture(requestId, future);
    } else {
      future.completeExceptionally(new ProtocolException("Client not connected"));
    }
    return future;
  }

  /**
   * Handles an identifiable response.
   */
  @SuppressWarnings("unchecked")
  private void handleResponse(long id, ByteBuffer response) {
    ResponseHolder holder = responses.remove(id);
    if (holder != null) {
      vertx.cancelTimer(holder.timer);
      holder.future.complete(response);
    }
  }

  /**
   * Handles an identifiable error.
   */
  private void handleError(long id, Throwable error) {
    ResponseHolder holder = responses.remove(id);
    if (holder != null) {
      vertx.cancelTimer(holder.timer);
      holder.future.completeExceptionally(error);
    }
  }

  /**
   * Stores a response callback by ID.
   */
  private void storeFuture(final long id, CompletableFuture<ByteBuffer> future) {
    long timerId = vertx.setTimer(5000, timer -> {
      handleError(id, new ProtocolException("Request timed out"));
    });
    ResponseHolder holder = new ResponseHolder(timerId, future);
    responses.put(id, holder);
  }

  @Override
  public CompletableFuture<Void> connect() {
    final CompletableFuture<Void> future = new CompletableFuture<>();

    if (client == null) {
      NetClientOptions options = new NetClientOptions()
        .setTcpKeepAlive(true)
        .setTcpNoDelay(true)
        .setSendBufferSize(protocol.getSendBufferSize())
        .setReceiveBufferSize(protocol.getReceiveBufferSize())
        .setSsl(protocol.isSsl())
        .setTrustAll(protocol.isClientTrustAll())
        .setUsePooledBuffers(true);
      client = vertx.createNetClient(options);
      client.connect(port, host, result -> {
        if (result.failed()) {
          future.completeExceptionally(result.cause());
        } else {
          socket = result.result();
          RecordParser parser = RecordParser.newFixed(4, null);
          Handler<Buffer> handler = new Handler<Buffer>() {
            int length = -1;
            @Override
            public void handle(Buffer buffer) {
              if (length == -1) {
                length = buffer.getInt(0);
                parser.fixedSizeMode(length + 8);
              } else {
                handleResponse(buffer.getLong(0), buffer.getBuffer(8, length + 8).getByteBuf().nioBuffer());
                length = -1;
                parser.fixedSizeMode(4);
              }
            }
          };
          parser.setOutput(handler);
          socket.handler(parser);
          future.complete(null);
        }
      });
    } else {
      future.complete(null);
    }
    return future;
  }

  @Override
  public CompletableFuture<Void> close() {
    final CompletableFuture<Void> future = new CompletableFuture<>();
    if (client != null && socket != null) {
      socket.closeHandler(v -> {
        socket = null;
        client.close();
        client = null;
        future.complete(null);
      }).close();
    } else if (client != null) {
      client.close();
      client = null;
      future.complete(null);
    } else {
      future.complete(null);
    }
    return future;
  }

  @Override
  public String toString() {
    return String.format("%s[host=%s, port=%d]", getClass().getSimpleName(), host, port);
  }

}
