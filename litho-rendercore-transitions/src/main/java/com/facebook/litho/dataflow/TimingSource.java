/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.dataflow;

/**
 * The component responsible for driving the progress of a {@link DataFlowBinding} on each frame
 * once it's been activated. This is generally just an Android Choreographer but is abstracted out
 * for testing.
 */
public interface TimingSource {

  void setDataFlowGraph(DataFlowGraph dataFlowGraph);

  /**
   * Registers the {@link DataFlowGraph} to receive frame callbacks until it calls {@link #stop()}.
   */
  void start();

  /** Stops the {@link DataFlowGraph} from receiving frame callbacks. */
  void stop();
}
