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
package io.trino.testing.assertions;

import io.airlift.units.Duration;
import org.gaul.modernizer_maven_annotations.SuppressModernizer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This class provides replacements for TestNG's faulty assertion methods.
 * <p>
 * So far, the reason for having this class is the
 * <a href="https://github.com/cbeust/testng/issues/543"> TestNG #543 -
 * Unexpected Behaviour: assertEquals for Iterable</a> bug,
 * which boils down to {@code assertEquals(Iterable, Iterable)} neglecting
 * any fields on the Iterable itself (only comparing its elements). This can
 * lead to false positive results in tests using the faulty assertion.
 */
@SuppressWarnings({"MethodOverridesStaticMethodOfSuperclass", "ExtendsUtilityClass"})
public class Assert
        extends org.testng.Assert
{
    private Assert() {}

    @SuppressModernizer // Assert.assertEquals(Iterable, Iterable) is forbidden, advising to use this class as a safety-adding wrapper.
    public static void assertEquals(Iterable<?> actual, Iterable<?> expected)
    {
        assertEquals(actual, expected, null);
    }

    @SuppressModernizer // Assert.assertEquals(Iterable, Iterable, String) is forbidden, advising to use this class as a safety-adding wrapper.
    public static void assertEquals(Iterable<?> actual, Iterable<?> expected, String message)
    {
        try {
            //do a full, equals-based check first
            org.testng.Assert.assertEquals((Object) actual, (Object) expected, message);
        }
        catch (AssertionError error) {
            //do the check again using Iterable-dedicated variant for a better error message.
            org.testng.Assert.assertEquals(actual, expected, message);
            //if we're here, the Iterables differ on their fields. Use the original error message.
            throw error;
        }
    }

    public static <E extends Exception> void assertEventually(CheckedRunnable<E> assertion)
            throws E
    {
        assertEventually(new Duration(30, SECONDS), assertion);
    }

    public static <E extends Exception> void assertEventually(Duration timeout, CheckedRunnable<E> assertion)
            throws E
    {
        assertEventually(timeout, new Duration(50, MILLISECONDS), assertion);
    }

    public static <E extends Exception> void assertEventually(Duration timeout, Duration retryFrequency, CheckedRunnable<E> assertion)
            throws E
    {
        long start = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                assertion.run();
                return;
            }
            catch (Exception | AssertionError e) {
                if (Duration.nanosSince(start).compareTo(timeout) > 0) {
                    throw e;
                }
            }
            try {
                Thread.sleep(retryFrequency.toMillis());
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    public interface CheckedRunnable<E extends Exception>
    {
        void run()
                throws E;
    }
}
