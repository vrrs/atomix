/*
 * Copyright 2014-present Open Networking Laboratory
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
package io.atomix.event;

/**
 * Entity capable of receiving events.
 */
@FunctionalInterface
public interface EventListener<E extends Event> extends EventFilter<E> {

  /**
   * Reacts to the specified event.
   *
   * @param event event to be processed
   */
  void onEvent(E event);

}
