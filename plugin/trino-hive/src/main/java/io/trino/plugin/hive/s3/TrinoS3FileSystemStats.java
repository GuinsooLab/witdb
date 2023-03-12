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
package io.trino.plugin.hive.s3;

import com.amazonaws.AbortedException;
import com.amazonaws.metrics.RequestMetricCollector;
import io.airlift.stats.CounterStat;
import io.trino.plugin.hive.aws.AwsSdkClientCoreStats;
import org.weakref.jmx.Flatten;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import java.net.SocketException;
import java.net.SocketTimeoutException;

public class TrinoS3FileSystemStats
{
    private final CounterStat activeConnections = new CounterStat();
    private final CounterStat startedUploads = new CounterStat();
    private final CounterStat failedUploads = new CounterStat();
    private final CounterStat successfulUploads = new CounterStat();
    private final CounterStat metadataCalls = new CounterStat();
    private final CounterStat listStatusCalls = new CounterStat();
    private final CounterStat listLocatedStatusCalls = new CounterStat();
    private final CounterStat listObjectsCalls = new CounterStat();
    private final CounterStat otherReadErrors = new CounterStat();
    private final CounterStat awsAbortedExceptions = new CounterStat();
    private final CounterStat socketExceptions = new CounterStat();
    private final CounterStat socketTimeoutExceptions = new CounterStat();
    private final CounterStat getObjectErrors = new CounterStat();
    private final CounterStat getMetadataErrors = new CounterStat();
    private final CounterStat initiateMultipartUploadErrors = new CounterStat();
    private final CounterStat getObjectRetries = new CounterStat();
    private final CounterStat getMetadataRetries = new CounterStat();
    private final CounterStat readRetries = new CounterStat();
    private final CounterStat initiateMultipartUploadRetries = new CounterStat();

    // see AWSRequestMetrics
    private final AwsSdkClientCoreStats clientCoreStats = new AwsSdkClientCoreStats();

    @Managed
    @Nested
    public CounterStat getActiveConnections()
    {
        return activeConnections;
    }

    @Managed
    @Nested
    public CounterStat getStartedUploads()
    {
        return startedUploads;
    }

    @Managed
    @Nested
    public CounterStat getFailedUploads()
    {
        return failedUploads;
    }

    @Managed
    @Nested
    public CounterStat getSuccessfulUploads()
    {
        return successfulUploads;
    }

    @Managed
    @Nested
    public CounterStat getMetadataCalls()
    {
        return metadataCalls;
    }

    @Managed
    @Nested
    public CounterStat getListStatusCalls()
    {
        return listStatusCalls;
    }

    @Managed
    @Nested
    public CounterStat getListLocatedStatusCalls()
    {
        return listLocatedStatusCalls;
    }

    @Managed
    @Nested
    public CounterStat getListObjectsCalls()
    {
        return listObjectsCalls;
    }

    @Managed
    @Nested
    public CounterStat getGetObjectErrors()
    {
        return getObjectErrors;
    }

    @Managed
    @Nested
    public CounterStat getGetMetadataErrors()
    {
        return getMetadataErrors;
    }

    @Managed
    @Nested
    public CounterStat getInitiateMultipartUploadErrors()
    {
        return initiateMultipartUploadErrors;
    }

    @Managed
    @Nested
    public CounterStat getOtherReadErrors()
    {
        return otherReadErrors;
    }

    @Managed
    @Nested
    public CounterStat getSocketExceptions()
    {
        return socketExceptions;
    }

    @Managed
    @Nested
    public CounterStat getSocketTimeoutExceptions()
    {
        return socketTimeoutExceptions;
    }

    @Managed
    @Nested
    public CounterStat getAwsAbortedExceptions()
    {
        return awsAbortedExceptions;
    }

    @Managed
    @Flatten
    public AwsSdkClientCoreStats getClientCoreStats()
    {
        return clientCoreStats;
    }

    @Managed
    @Nested
    public CounterStat getGetObjectRetries()
    {
        return getObjectRetries;
    }

    @Managed
    @Nested
    public CounterStat getGetMetadataRetries()
    {
        return getMetadataRetries;
    }

    @Managed
    @Nested
    public CounterStat getReadRetries()
    {
        return readRetries;
    }

    @Managed
    @Nested
    public CounterStat getInitiateMultipartUploadRetries()
    {
        return initiateMultipartUploadRetries;
    }

    public RequestMetricCollector newRequestMetricCollector()
    {
        return clientCoreStats.newRequestMetricCollector();
    }

    public void connectionOpened()
    {
        activeConnections.update(1);
    }

    public void connectionReleased()
    {
        activeConnections.update(-1);
    }

    public void uploadStarted()
    {
        startedUploads.update(1);
    }

    public void uploadFailed()
    {
        failedUploads.update(1);
    }

    public void uploadSuccessful()
    {
        successfulUploads.update(1);
    }

    public void newMetadataCall()
    {
        metadataCalls.update(1);
    }

    public void newListStatusCall()
    {
        listStatusCalls.update(1);
    }

    public void newListLocatedStatusCall()
    {
        listLocatedStatusCalls.update(1);
    }

    public void newListObjectsCall()
    {
        listObjectsCalls.update(1);
    }

    public void newReadError(Throwable t)
    {
        if (t instanceof SocketException) {
            socketExceptions.update(1);
        }
        else if (t instanceof SocketTimeoutException) {
            socketTimeoutExceptions.update(1);
        }
        else if (t instanceof AbortedException) {
            awsAbortedExceptions.update(1);
        }
        else {
            otherReadErrors.update(1);
        }
    }

    public void newGetObjectError()
    {
        getObjectErrors.update(1);
    }

    public void newGetMetadataError()
    {
        getMetadataErrors.update(1);
    }

    public void newInitiateMultipartUploadError()
    {
        initiateMultipartUploadErrors.update(1);
    }

    public void newGetObjectRetry()
    {
        getObjectRetries.update(1);
    }

    public void newGetMetadataRetry()
    {
        getMetadataRetries.update(1);
    }

    public void newReadRetry()
    {
        readRetries.update(1);
    }

    public void newInitiateMultipartUploadRetry()
    {
        initiateMultipartUploadRetries.update(1);
    }
}
