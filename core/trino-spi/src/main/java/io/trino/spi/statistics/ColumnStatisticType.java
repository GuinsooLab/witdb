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
package io.trino.spi.statistics;

public enum ColumnStatisticType
{
    @Deprecated
    MIN_VALUE,
    MAX_VALUE,
    @Deprecated
    NUMBER_OF_DISTINCT_VALUES,
    NUMBER_OF_DISTINCT_VALUES_SUMMARY,
    @Deprecated
    NUMBER_OF_NON_NULL_VALUES,
    @Deprecated
    NUMBER_OF_TRUE_VALUES,
    MAX_VALUE_SIZE_IN_BYTES,
    TOTAL_SIZE_IN_BYTES,
}
