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
package io.trino.dispatcher;

import com.google.common.io.Closer;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import io.airlift.concurrent.ThreadPoolExecutorMBean;
import io.trino.execution.QueryManagerConfig;
import io.trino.spi.VersionEmbedder;
import org.weakref.jmx.Flatten;
import org.weakref.jmx.Managed;
import org.weakref.jmx.Nested;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;

public class DispatchExecutor
{
    @SuppressWarnings("resource")
    private final Closer closer = Closer.create();

    private final ListeningExecutorService executor;
    private final ListeningScheduledExecutorService scheduledExecutor;

    private final DispatchExecutorMBeans mbeans;

    @Inject
    public DispatchExecutor(QueryManagerConfig config, VersionEmbedder versionEmbedder)
    {
        ExecutorService coreExecutor = newCachedThreadPool(daemonThreadsNamed("dispatcher-query-%s"));
        closer.register(coreExecutor::shutdownNow);
        executor = new DecoratingListeningExecutorService(
                listeningDecorator(coreExecutor),
                new DecoratingListeningExecutorService.TaskDecorator()
                {
                    @Override
                    public Runnable decorate(Runnable command)
                    {
                        return versionEmbedder.embedVersion(command);
                    }

                    @Override
                    public <T> Callable<T> decorate(Callable<T> task)
                    {
                        return versionEmbedder.embedVersion(task);
                    }
                });

        ScheduledExecutorService coreScheduledExecutor = newScheduledThreadPool(config.getQueryManagerExecutorPoolSize(), daemonThreadsNamed("dispatch-executor-%s"));
        closer.register(coreScheduledExecutor::shutdownNow);
        scheduledExecutor = listeningDecorator(coreScheduledExecutor);

        mbeans = new DispatchExecutorMBeans(coreExecutor, coreScheduledExecutor);
    }

    public ListeningExecutorService getExecutor()
    {
        return executor;
    }

    public ListeningScheduledExecutorService getScheduledExecutor()
    {
        return scheduledExecutor;
    }

    @Managed
    @Flatten
    public DispatchExecutorMBeans getMbeans()
    {
        return mbeans;
    }

    @PreDestroy
    public void shutdown()
            throws Exception
    {
        closer.close();
    }

    public static class DispatchExecutorMBeans
    {
        private final ThreadPoolExecutorMBean executor;
        private final ThreadPoolExecutorMBean scheduledExecutor;

        public DispatchExecutorMBeans(ExecutorService coreExecutor, ScheduledExecutorService coreScheduledExecutor)
        {
            requireNonNull(coreExecutor, "coreExecutor is null");
            requireNonNull(coreScheduledExecutor, "coreScheduledExecutor is null");
            executor = new ThreadPoolExecutorMBean((ThreadPoolExecutor) coreExecutor);
            scheduledExecutor = new ThreadPoolExecutorMBean((ThreadPoolExecutor) coreScheduledExecutor);
        }

        @Managed
        @Nested
        public ThreadPoolExecutorMBean getExecutor()
        {
            return executor;
        }

        @Managed
        @Nested
        public ThreadPoolExecutorMBean getScheduledExecutor()
        {
            return scheduledExecutor;
        }
    }
}
