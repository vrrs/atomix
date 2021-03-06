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
package io.atomix.protocols.raft.storage.log.entry;

import io.atomix.protocols.raft.ReadConsistency;
import io.atomix.utils.TimestampPrinter;

import static com.google.common.base.MoreObjects.toStringHelper;

/**
 * Open session entry.
 */
public class OpenSessionEntry extends TimestampedEntry {
  private final String memberId;
  private final String serviceName;
  private final String serviceType;
  private final ReadConsistency readConsistency;
  private final long timeout;

  public OpenSessionEntry(long term, long timestamp, String memberId, String serviceName, String serviceType, ReadConsistency readConsistency, long timeout) {
    super(term, timestamp);
    this.memberId = memberId;
    this.serviceName = serviceName;
    this.serviceType = serviceType;
    this.readConsistency = readConsistency;
    this.timeout = timeout;
  }

  /**
   * Returns the client node identifier.
   *
   * @return The client node identifier.
   */
  public String memberId() {
    return memberId;
  }

  /**
   * Returns the session state machine name.
   *
   * @return The session's state machine name.
   */
  public String serviceName() {
    return serviceName;
  }

  /**
   * Returns the session state machine type name.
   *
   * @return The session's state machine type name.
   */
  public String serviceType() {
    return serviceType;
  }

  /**
   * Returns the session read consistency level.
   *
   * @return The session's read consistency level.
   */
  public ReadConsistency readConsistency() {
    return readConsistency;
  }

  /**
   * Returns the session timeout.
   *
   * @return The session timeout.
   */
  public long timeout() {
    return timeout;
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("term", term)
        .add("timestamp", new TimestampPrinter(timestamp))
        .add("node", memberId)
        .add("serviceName", serviceName)
        .add("serviceType", serviceType)
        .add("readConsistency", readConsistency)
        .add("timeout", timeout)
        .toString();
  }
}
