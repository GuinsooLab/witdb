/*
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
package io.trino.execution.buffer;

import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import io.trino.execution.StateMachine.StateChangeListener;

import java.util.List;
import java.util.Optional;

public interface OutputBuffer
{
    /**
     * Gets the current state of this buffer.  This method is guaranteed to not block or acquire
     * contended locks, but the stats in the info object may be internally inconsistent.
     */
    OutputBufferInfo getInfo();

    /**
     * Get buffer state
     */
    BufferState getState();

    /**
     * Get the memory utilization percentage.
     */
    double getUtilization();

    /**
     * Get buffer status
     */
    OutputBufferStatus getStatus();

    /**
     * Add a listener which fires anytime the buffer state changes.
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor. Additionally, it is
     * possible notifications are observed out of order due to the asynchronous execution.
     */
    void addStateChangeListener(StateChangeListener<BufferState> stateChangeListener);

    /**
     * Updates the buffer configuration.
     */
    void setOutputBuffers(OutputBuffers newOutputBuffers);

    /**
     * Gets pages from the output buffer, and acknowledges all pages received from the last
     * request.  The initial token is zero. Subsequent tokens are acquired from the
     * next token field in the BufferResult returned from the previous request.
     * If the buffer result is marked as complete, the client must call abort to acknowledge
     * receipt of the final state.
     */
    ListenableFuture<BufferResult> get(PipelinedOutputBuffers.OutputBufferId bufferId, long token, DataSize maxSize);

    /**
     * Acknowledges the previously received pages from the output buffer.
     */
    void acknowledge(PipelinedOutputBuffers.OutputBufferId bufferId, long token);

    /**
     * Destroys the specified output buffer, discarding all pages.
     */
    void destroy(PipelinedOutputBuffers.OutputBufferId bufferId);

    /**
     * Get a future that will be completed when the buffer is not full.
     */
    ListenableFuture<Void> isFull();

    /**
     * Adds a split-up page to an unpartitioned buffer. If no-more-pages has been set, the enqueue
     * page call is ignored.  This can happen with limit queries.
     */
    void enqueue(List<Slice> pages);

    /**
     * Adds a split-up page to a specific partition.  If no-more-pages has been set, the enqueue
     * page call is ignored.  This can happen with limit queries.
     */
    void enqueue(int partition, List<Slice> pages);

    /**
     * Notify buffer that no more pages will be added. Any future calls to enqueue a
     * page are ignored.
     */
    void setNoMorePages();

    /**
     * Destroys the buffer, discarding all pages.
     */
    void destroy();

    /**
     * Abort the buffer, discarding all pages, but blocking readers.  It is expected that
     * readers will be unblocked when the failed query is cleaned up.
     */
    void abort();

    /**
     * @return the peak memory usage of this output buffer.
     */
    long getPeakMemoryUsage();

    /**
     * Returns non empty failure cause if the buffer is in state {@link BufferState#FAILED}
     */
    Optional<Throwable> getFailureCause();
}
