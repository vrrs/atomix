/*
 * Copyright 2017-present Open Networking Laboratory
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
package io.atomix.protocols.raft;

import com.google.common.collect.Maps;
import io.atomix.messaging.Endpoint;
import io.atomix.messaging.netty.NettyMessagingManager;
import io.atomix.protocols.raft.cluster.MemberId;
import io.atomix.protocols.raft.cluster.RaftMember;
import io.atomix.protocols.raft.cluster.impl.DefaultRaftMember;
import io.atomix.protocols.raft.operation.OperationId;
import io.atomix.protocols.raft.operation.OperationType;
import io.atomix.protocols.raft.operation.RaftOperation;
import io.atomix.protocols.raft.operation.impl.DefaultOperationId;
import io.atomix.protocols.raft.protocol.AppendRequest;
import io.atomix.protocols.raft.protocol.AppendResponse;
import io.atomix.protocols.raft.protocol.CloseSessionRequest;
import io.atomix.protocols.raft.protocol.CloseSessionResponse;
import io.atomix.protocols.raft.protocol.CommandRequest;
import io.atomix.protocols.raft.protocol.CommandResponse;
import io.atomix.protocols.raft.protocol.ConfigureRequest;
import io.atomix.protocols.raft.protocol.ConfigureResponse;
import io.atomix.protocols.raft.protocol.InstallRequest;
import io.atomix.protocols.raft.protocol.InstallResponse;
import io.atomix.protocols.raft.protocol.JoinRequest;
import io.atomix.protocols.raft.protocol.JoinResponse;
import io.atomix.protocols.raft.protocol.KeepAliveRequest;
import io.atomix.protocols.raft.protocol.KeepAliveResponse;
import io.atomix.protocols.raft.protocol.LeaveRequest;
import io.atomix.protocols.raft.protocol.LeaveResponse;
import io.atomix.protocols.raft.protocol.LocalRaftProtocolFactory;
import io.atomix.protocols.raft.protocol.MetadataRequest;
import io.atomix.protocols.raft.protocol.MetadataResponse;
import io.atomix.protocols.raft.protocol.OpenSessionRequest;
import io.atomix.protocols.raft.protocol.OpenSessionResponse;
import io.atomix.protocols.raft.protocol.PollRequest;
import io.atomix.protocols.raft.protocol.PollResponse;
import io.atomix.protocols.raft.protocol.PublishRequest;
import io.atomix.protocols.raft.protocol.QueryRequest;
import io.atomix.protocols.raft.protocol.QueryResponse;
import io.atomix.protocols.raft.protocol.RaftClientMessagingProtocol;
import io.atomix.protocols.raft.protocol.RaftClientProtocol;
import io.atomix.protocols.raft.protocol.RaftResponse;
import io.atomix.protocols.raft.protocol.RaftServerMessagingProtocol;
import io.atomix.protocols.raft.protocol.RaftServerProtocol;
import io.atomix.protocols.raft.protocol.ReconfigureRequest;
import io.atomix.protocols.raft.protocol.ReconfigureResponse;
import io.atomix.protocols.raft.protocol.ResetRequest;
import io.atomix.protocols.raft.protocol.VoteRequest;
import io.atomix.protocols.raft.protocol.VoteResponse;
import io.atomix.protocols.raft.proxy.CommunicationStrategy;
import io.atomix.protocols.raft.proxy.RaftProxy;
import io.atomix.protocols.raft.service.AbstractRaftService;
import io.atomix.protocols.raft.service.Commit;
import io.atomix.protocols.raft.service.RaftServiceExecutor;
import io.atomix.protocols.raft.session.SessionId;
import io.atomix.protocols.raft.storage.RaftStorage;
import io.atomix.protocols.raft.storage.log.entry.CloseSessionEntry;
import io.atomix.protocols.raft.storage.log.entry.CommandEntry;
import io.atomix.protocols.raft.storage.log.entry.ConfigurationEntry;
import io.atomix.protocols.raft.storage.log.entry.InitializeEntry;
import io.atomix.protocols.raft.storage.log.entry.KeepAliveEntry;
import io.atomix.protocols.raft.storage.log.entry.MetadataEntry;
import io.atomix.protocols.raft.storage.log.entry.OpenSessionEntry;
import io.atomix.protocols.raft.storage.log.entry.QueryEntry;
import io.atomix.protocols.raft.storage.snapshot.SnapshotReader;
import io.atomix.protocols.raft.storage.snapshot.SnapshotWriter;
import io.atomix.protocols.raft.storage.system.Configuration;
import io.atomix.serializer.Serializer;
import io.atomix.serializer.kryo.KryoNamespace;
import io.atomix.storage.StorageLevel;
import io.atomix.utils.concurrent.Scheduled;
import io.atomix.utils.concurrent.Scheduler;
import io.atomix.utils.concurrent.SingleThreadContext;
import io.atomix.utils.concurrent.ThreadContext;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Raft fuzz test.
 */
public class RaftFuzzTest implements Runnable {

  private static final boolean USE_NETTY = true;

  private static final int ITERATIONS = 1000;
  private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  private static final CommunicationStrategy COMMUNICATION_STRATEGY = CommunicationStrategy.ANY;

  /**
   * Runs the test.
   */
  public static void main(String[] args) {
    new RaftFuzzTest().run();
  }

  private static final Serializer protocolSerializer = Serializer.using(KryoNamespace.newBuilder()
      .register(OpenSessionRequest.class)
      .register(OpenSessionResponse.class)
      .register(CloseSessionRequest.class)
      .register(CloseSessionResponse.class)
      .register(KeepAliveRequest.class)
      .register(KeepAliveResponse.class)
      .register(QueryRequest.class)
      .register(QueryResponse.class)
      .register(CommandRequest.class)
      .register(CommandResponse.class)
      .register(MetadataRequest.class)
      .register(MetadataResponse.class)
      .register(JoinRequest.class)
      .register(JoinResponse.class)
      .register(LeaveRequest.class)
      .register(LeaveResponse.class)
      .register(ConfigureRequest.class)
      .register(ConfigureResponse.class)
      .register(ReconfigureRequest.class)
      .register(ReconfigureResponse.class)
      .register(InstallRequest.class)
      .register(InstallResponse.class)
      .register(PollRequest.class)
      .register(PollResponse.class)
      .register(VoteRequest.class)
      .register(VoteResponse.class)
      .register(AppendRequest.class)
      .register(AppendResponse.class)
      .register(PublishRequest.class)
      .register(ResetRequest.class)
      .register(RaftResponse.Status.class)
      .register(RaftError.class)
      .register(RaftError.Type.class)
      .register(RaftOperation.class)
      .register(ReadConsistency.class)
      .register(byte[].class)
      .register(long[].class)
      .register(CloseSessionEntry.class)
      .register(CommandEntry.class)
      .register(ConfigurationEntry.class)
      .register(InitializeEntry.class)
      .register(KeepAliveEntry.class)
      .register(MetadataEntry.class)
      .register(OpenSessionEntry.class)
      .register(QueryEntry.class)
      .register(RaftOperation.class)
      .register(DefaultOperationId.class)
      .register(OperationType.class)
      .register(ReadConsistency.class)
      .register(ArrayList.class)
      .register(Collections.emptyList().getClass())
      .register(HashSet.class)
      .register(DefaultRaftMember.class)
      .register(MemberId.class)
      .register(SessionId.class)
      .register(RaftMember.Type.class)
      .register(Instant.class)
      .register(Configuration.class)
      .build());

  private static final Serializer storageSerializer = Serializer.using(KryoNamespace.newBuilder()
      .register(CloseSessionEntry.class)
      .register(CommandEntry.class)
      .register(ConfigurationEntry.class)
      .register(InitializeEntry.class)
      .register(KeepAliveEntry.class)
      .register(MetadataEntry.class)
      .register(OpenSessionEntry.class)
      .register(QueryEntry.class)
      .register(RaftOperation.class)
      .register(DefaultOperationId.class)
      .register(OperationType.class)
      .register(ReadConsistency.class)
      .register(ArrayList.class)
      .register(HashSet.class)
      .register(DefaultRaftMember.class)
      .register(MemberId.class)
      .register(RaftMember.Type.class)
      .register(Instant.class)
      .register(Configuration.class)
      .register(byte[].class)
      .register(long[].class)
      .build());

  private static final Serializer clientSerializer = Serializer.using(KryoNamespace.newBuilder()
      .register(ReadConsistency.class)
      .register(Maps.immutableEntry("", "").getClass())
      .build());

  private int nextId;
  private int port = 5000;
  private List<RaftMember> members = new ArrayList<>();
  private List<RaftClient> clients = new ArrayList<>();
  private List<RaftServer> servers = new ArrayList<>();
  private Map<Integer, Scheduled> shutdownTimers = new ConcurrentHashMap<>();
  private Map<Integer, Scheduled> restartTimers = new ConcurrentHashMap<>();
  private LocalRaftProtocolFactory protocolFactory;
  private List<NettyMessagingManager> messagingManagers = new ArrayList<>();
  private Map<MemberId, Endpoint> endpointMap = new ConcurrentHashMap<>();
  private static final String[] KEYS = new String[1024];
  private final Random random = new Random();

  static {
    for (int i = 0; i < 1024; i++) {
      KEYS[i] = UUID.randomUUID().toString();
    }
  }

  @Override
  public void run() {
    for (int i = 0; i < ITERATIONS; i++) {
      try {
        runFuzzTest();
      } catch (Exception e) {
        e.printStackTrace();
        return;
      }
    }
  }

  /**
   * Returns a random map key.
   */
  private String randomKey() {
    return KEYS[randomNumber(KEYS.length)];
  }

  /**
   * Returns a random query consistency level.
   */
  private ReadConsistency randomConsistency() {
    return ReadConsistency.values()[randomNumber(ReadConsistency.values().length)];
  }

  /**
   * Returns a random number within the given range.
   */
  private int randomNumber(int limit) {
    return random.nextInt(limit);
  }

  /**
   * Returns a random boolean.
   */
  private boolean randomBoolean() {
    return randomNumber(2) == 1;
  }

  /**
   * Returns a random string up to the given length.
   */
  private String randomString(int maxLength) {
    int length = randomNumber(maxLength) + 1;
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
    }
    return sb.toString();
  }

  /**
   * Runs a single fuzz test.
   */
  private void runFuzzTest() throws Exception {
    reset();

    createServers(randomNumber(5) + 3);

    final Object lock = new Object();
    final AtomicLong index = new AtomicLong();
    final Map<Integer, Long> indexes = new HashMap<>();

    ThreadContext context = new SingleThreadContext("fuzz-test");

    int clients = randomNumber(10) + 1;
    for (int i = 0; i < clients; i++) {
      ReadConsistency consistency = randomConsistency();
      RaftClient client = createClient();
      RaftProxy proxy = createProxy(client, consistency);
      Scheduler scheduler = new SingleThreadContext("fuzz-test-" + i);

      final int clientId = i;
      scheduler.schedule(Duration.ofMillis((100 * clients) + (randomNumber(50) - 25)), Duration.ofMillis((100 * clients) + (randomNumber(50) - 25)), () -> {
        long lastLinearizableIndex = index.get();
        int type = randomNumber(4);
        switch (type) {
          case 0:
            proxy.<Map.Entry<String, String>, Long>invoke(PUT, clientSerializer::encode, Maps.immutableEntry(randomKey(), randomString(1024 * 16)), clientSerializer::decode).thenAccept(result -> {
              synchronized (lock) {
                if (result < lastLinearizableIndex) {
                  System.out.println(result + " is less than last linearizable index " + lastLinearizableIndex);
                  System.exit(1);
                } else if (result > index.get()) {
                  index.set(result);
                }

                Long lastSequentialIndex = indexes.get(clientId);
                if (lastSequentialIndex == null) {
                  indexes.put(clientId, result);
                } else if (result < lastSequentialIndex) {
                  System.out.println(result + " is less than last sequential index " + lastSequentialIndex);
                  System.exit(1);
                } else {
                  indexes.put(clientId, lastSequentialIndex);
                }
              }
            });
            break;
          case 1:
            proxy.invoke(GET, clientSerializer::encode, randomKey(), clientSerializer::decode);
            break;
          case 2:
            proxy.<String, Long>invoke(REMOVE, clientSerializer::encode, randomKey(), clientSerializer::decode).thenAccept(result -> {
              synchronized (lock) {
                if (result < lastLinearizableIndex) {
                  System.out.println(result + " is less than last linearizable index " + lastLinearizableIndex);
                  System.exit(1);
                } else if (result > index.get()) {
                  index.set(result);
                }

                Long lastSequentialIndex = indexes.get(clientId);
                if (lastSequentialIndex == null) {
                  indexes.put(clientId, result);
                } else if (result < lastSequentialIndex) {
                  System.out.println(result + " is less than last sequential index " + lastSequentialIndex);
                  System.exit(1);
                } else {
                  indexes.put(clientId, lastSequentialIndex);
                }
              }
            });
            break;
          case 3:
            proxy.<Long>invoke(INDEX, clientSerializer::decode).thenAccept(result -> {
              synchronized (lock) {
                switch (consistency) {
                  case LINEARIZABLE:
                  case LINEARIZABLE_LEASE:
                    if (result < lastLinearizableIndex) {
                      System.out.println(result + " is less than last linearizable index " + lastLinearizableIndex);
                      System.exit(1);
                    } else if (result > index.get()) {
                      index.set(result);
                    }
                  case SEQUENTIAL:
                    Long lastSequentialIndex = indexes.get(clientId);
                    if (lastSequentialIndex == null) {
                      indexes.put(clientId, result);
                    } else if (result < lastSequentialIndex) {
                      System.out.println(result + " is less than last sequential index " + lastSequentialIndex);
                      System.exit(1);
                    } else {
                      indexes.put(clientId, lastSequentialIndex);
                    }
                }
              }
            });
        }
      });
    }

    scheduleRestarts(context);

    Thread.sleep(Duration.ofMinutes(15).toMillis());
  }

  /**
   * Schedules a random number of servers to be shutdown for a period of time and then restarted.
   */
  private void scheduleRestarts(ThreadContext context) {
    if (shutdownTimers.isEmpty() && restartTimers.isEmpty()) {
      int shutdownCount = randomNumber(servers.size() - 2) + 1;
      boolean remove = randomBoolean();
      for (int i = 0; i < shutdownCount; i++) {
        scheduleRestart(remove, i, context);
      }
    }
  }

  /**
   * Schedules the given server to be shutdown for a period of time and then restarted.
   */
  private void scheduleRestart(boolean remove, int serverIndex, ThreadContext context) {
    shutdownTimers.put(serverIndex, context.schedule(Duration.ofSeconds(randomNumber(120) + 10), () -> {
      shutdownTimers.remove(serverIndex);
      RaftServer server = servers.get(serverIndex);
      CompletableFuture<Void> leaveFuture;
      if (remove) {
        System.out.println("Removing server: " + server.cluster().getMember().memberId());
        leaveFuture = server.leave();
      } else {
        System.out.println("Shutting down server: " + server.cluster().getMember().memberId());
        leaveFuture = server.shutdown();
      }
      leaveFuture.whenComplete((result, error) -> {
        restartTimers.put(serverIndex, context.schedule(Duration.ofSeconds(randomNumber(120) + 10), () -> {
          restartTimers.remove(serverIndex);
          RaftServer newServer = createServer(server.cluster().getMember());
          servers.set(serverIndex, newServer);
          CompletableFuture<RaftServer> joinFuture;
          if (remove) {
            System.out.println("Adding server: " + newServer.cluster().getMember().memberId());
            joinFuture = newServer.join(members.get(members.size() - 1).memberId());
          } else {
            System.out.println("Bootstrapping server: " + newServer.cluster().getMember().memberId());
            joinFuture = newServer.bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList()));
          }
          joinFuture.whenComplete((result2, error2) -> {
            scheduleRestarts(context);
          });
        }));
      });
    }));
  }

  /**
   * Shuts down clients and servers.
   */
  private void reset() throws Exception {
    for (Scheduled shutdownTimer : shutdownTimers.values()) {
      shutdownTimer.cancel();
    }
    shutdownTimers.clear();

    for (Scheduled restartTimer : restartTimers.values()) {
      restartTimer.cancel();
    }
    restartTimers.clear();

    clients.forEach(c -> {
      try {
        c.close().get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
      }
    });

    servers.forEach(s -> {
      try {
        if (s.isRunning()) {
          s.shutdown().get(10, TimeUnit.SECONDS);
        }
      } catch (Exception e) {
      }
    });

    Path directory = Paths.get("target/fuzz-logs/");
    if (Files.exists(directory)) {
      Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    }

    members = new ArrayList<>();
    port = 5000;
    clients = new ArrayList<>();
    servers = new ArrayList<>();
    protocolFactory = new LocalRaftProtocolFactory(protocolSerializer);
  }

  /**
   * Returns the next unique member identifier.
   *
   * @return The next unique member identifier.
   */
  private MemberId nextMemberId() {
    return MemberId.from(String.valueOf(++nextId));
  }

  /**
   * Returns the next server address.
   *
   * @param type The startup member type.
   * @return The next server address.
   */
  private RaftMember nextMember(RaftMember.Type type) {
    return new TestMember(nextMemberId(), type);
  }

  /**
   * Creates a set of Raft servers.
   */
  private List<RaftServer> createServers(int nodes) throws Exception {
    List<RaftServer> servers = new ArrayList<>();

    for (int i = 0; i < nodes; i++) {
      members.add(nextMember(RaftMember.Type.ACTIVE));
    }

    CountDownLatch latch = new CountDownLatch(nodes);
    for (int i = 0; i < nodes; i++) {
      RaftServer server = createServer(members.get(i));
      server.bootstrap(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).thenRun(latch::countDown);
      servers.add(server);
    }

    latch.await(30000, TimeUnit.MILLISECONDS);

    return servers;
  }

  /**
   * Creates a Raft server.
   */
  private RaftServer createServer(RaftMember member) {
    RaftServerProtocol protocol;
    if (USE_NETTY) {
      try {
        Endpoint endpoint = new Endpoint(InetAddress.getLocalHost(), ++port);
        NettyMessagingManager messagingManager = new NettyMessagingManager(endpoint);
        messagingManagers.add(messagingManager);
        endpointMap.put(member.memberId(), endpoint);
        protocol = new RaftServerMessagingProtocol(messagingManager, protocolSerializer, endpointMap::get);
      } catch (UnknownHostException e) {
        throw new RuntimeException(e);
      }
    } else {
      protocol = protocolFactory.newServerProtocol(member.memberId());
    }

    RaftServer.Builder builder = RaftServer.newBuilder(member.memberId())
        .withType(member.getType())
        .withProtocol(protocol)
        .withStorage(RaftStorage.newBuilder()
            .withStorageLevel(StorageLevel.DISK)
            .withDirectory(new File(String.format("target/fuzz-logs/%s", member.memberId())))
            .withSerializer(storageSerializer)
            .withMaxSegmentSize(1024 * 1024)
            .build())
        .addService("test", FuzzStateMachine::new);

    RaftServer server = builder.build();
    servers.add(server);
    return server;
  }

  /**
   * Creates a Raft client.
   */
  private RaftClient createClient() throws Exception {
    MemberId memberId = nextMemberId();

    RaftClientProtocol protocol;
    if (USE_NETTY) {
      Endpoint endpoint = new Endpoint(InetAddress.getLocalHost(), ++port);
      NettyMessagingManager messagingManager = new NettyMessagingManager(endpoint);
      endpointMap.put(memberId, endpoint);
      protocol = new RaftClientMessagingProtocol(messagingManager, protocolSerializer, endpointMap::get);
    } else {
      protocol = protocolFactory.newClientProtocol(memberId);
    }

    RaftClient client = RaftClient.newBuilder()
        .withMemberId(memberId)
        .withProtocol(protocol)
        .build();

    client.connect(members.stream().map(RaftMember::memberId).collect(Collectors.toList())).join();
    clients.add(client);
    return client;
  }

  /**
   * Creates a test session.
   */
  private RaftProxy createProxy(RaftClient client, ReadConsistency consistency) {
    return client.newProxyBuilder()
        .withName("test")
        .withServiceType("test")
        .withReadConsistency(consistency)
        .withCommunicationStrategy(COMMUNICATION_STRATEGY)
        .build()
        .open()
        .join();
  }

  private static final OperationId PUT = OperationId.command("put");
  private static final OperationId GET = OperationId.query("get");
  private static final OperationId REMOVE = OperationId.command("remove");
  private static final OperationId INDEX = OperationId.command("index");

  /**
   * Fuzz test state machine.
   */
  public class FuzzStateMachine extends AbstractRaftService {
    private Map<String, String> map = new HashMap<>();

    @Override
    protected void configure(RaftServiceExecutor executor) {
      executor.register(PUT, clientSerializer::decode, this::put, clientSerializer::encode);
      executor.register(GET, clientSerializer::decode, this::get, clientSerializer::encode);
      executor.register(REMOVE, clientSerializer::decode, this::remove, clientSerializer::encode);
      executor.register(INDEX, this::index, clientSerializer::encode);
    }

    @Override
    public void snapshot(SnapshotWriter writer) {
      writer.writeInt(map.size());
      for (Map.Entry<String, String> entry : map.entrySet()) {
        writer.writeString(entry.getKey());
        writer.writeString(entry.getValue());
      }
    }

    @Override
    public void install(SnapshotReader reader) {
      map = new HashMap<>();
      int size = reader.readInt();
      for (int i = 0; i < size; i++) {
        String key = reader.readString();
        String value = reader.readString();
        map.put(key, value);
      }
    }

    protected long put(Commit<Map.Entry<String, String>> commit) {
      map.put(commit.value().getKey(), commit.value().getValue());
      return commit.index();
    }

    protected String get(Commit<String> commit) {
      return map.get(commit.value());
    }

    protected long remove(Commit<String> commit) {
      map.remove(commit.value());
      return commit.index();
    }

    protected long index(Commit<Void> commit) {
      return commit.index();
    }
  }

  /**
   * Test member.
   */
  public static class TestMember implements RaftMember {
    private final MemberId memberId;
    private final Type type;

    public TestMember(MemberId memberId, Type type) {
      this.memberId = memberId;
      this.type = type;
    }

    @Override
    public MemberId memberId() {
      return memberId;
    }

    @Override
    public int hash() {
      return memberId.hashCode();
    }

    @Override
    public Type getType() {
      return type;
    }

    @Override
    public void addTypeChangeListener(Consumer<Type> listener) {

    }

    @Override
    public void removeTypeChangeListener(Consumer<Type> listener) {

    }

    @Override
    public Instant getLastUpdated() {
      return Instant.now();
    }

    @Override
    public CompletableFuture<Void> promote() {
      return null;
    }

    @Override
    public CompletableFuture<Void> promote(Type type) {
      return null;
    }

    @Override
    public CompletableFuture<Void> demote() {
      return null;
    }

    @Override
    public CompletableFuture<Void> demote(Type type) {
      return null;
    }

    @Override
    public CompletableFuture<Void> remove() {
      return null;
    }
  }

}
