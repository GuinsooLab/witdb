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
package io.trino;

import io.trino.spi.TrinoException;
import io.trino.transaction.TransactionId;

import static io.trino.spi.StandardErrorCode.UNKNOWN_TRANSACTION;
import static java.lang.String.format;

public class NotInTransactionException
        extends TrinoException
{
    public NotInTransactionException()
    {
        super(UNKNOWN_TRANSACTION, "Not in a transaction");
    }

    public NotInTransactionException(TransactionId transactionId)
    {
        super(UNKNOWN_TRANSACTION, format("Unknown transaction ID: %s. Possibly expired? Commands ignored until end of transaction block", transactionId));
    }
}
