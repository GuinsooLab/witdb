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
package io.trino.plugin.atop;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import io.trino.testing.QueryRunner;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.io.Resources.toByteArray;
import static io.trino.plugin.atop.AtopErrorCode.ATOP_READ_TIMEOUT;
import static io.trino.plugin.atop.LocalAtopQueryRunner.createQueryRunner;
import static io.trino.testing.assertions.TrinoExceptionAssert.assertTrinoExceptionThrownBy;
import static java.nio.file.Files.createTempDirectory;

public class TestAtopHang
{
    private QueryRunner queryRunner;

    @BeforeClass
    public void setUp()
            throws Exception
    {
        Path tempPath = createTempDirectory(null);
        copyExecutable("hanging_atop.sh", tempPath.toFile());
        queryRunner = createQueryRunner(ImmutableMap.of("atop.executable-path", tempPath + "/hanging_atop.sh", "atop.executable-read-timeout", "1s"), AtopProcessFactory.class);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        queryRunner.close();
        queryRunner = null;
    }

    @Test(timeOut = 60_000)
    public void testTimeout()
    {
        assertTrinoExceptionThrownBy(() -> queryRunner.execute("SELECT * FROM disks"))
                .hasErrorCode(ATOP_READ_TIMEOUT);
    }

    private void copyExecutable(String name, File target)
            throws IOException
    {
        byte[] bytes = toByteArray(Resources.getResource(getClass(), name));
        Path path = target.toPath().resolve(new File(name).getName());
        Files.write(path, bytes);
        if (!path.toFile().setExecutable(true)) {
            throw new IOException("failed to make executable: " + path);
        }
    }
}
