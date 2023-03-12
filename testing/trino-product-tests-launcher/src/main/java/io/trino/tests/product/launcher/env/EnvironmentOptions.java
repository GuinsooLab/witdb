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
package io.trino.tests.product.launcher.env;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.File;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.tests.product.launcher.env.EnvironmentContainers.COORDINATOR;
import static java.util.Locale.ENGLISH;
import static picocli.CommandLine.Option;

public final class EnvironmentOptions
{
    private static final String DEFAULT_VALUE = "(default: ${DEFAULT-VALUE})";
    public static final String BIND_ON_HOST = "on";
    public static final String DO_NOT_BIND = "off";

    @Spec
    private CommandSpec spec;

    @Option(names = "--config", paramLabel = "<config>", description = "Environment config to use")
    public String config = "config-default";

    @Option(names = "--server-package", paramLabel = "<package>", description = "Path to Trino server package " + DEFAULT_VALUE, defaultValue = "${server.module}/target/${server.name}-${project.version}.tar.gz")
    public File serverPackage;

    @Option(names = "--without-trino", description = "Do not start " + COORDINATOR)
    public boolean withoutTrinoMaster;

    public boolean bindPorts = true;
    int bindPortsBase;

    @Option(names = "--debug", description = "Open Java debug ports")
    public boolean debug;

    @Option(names = "--output", description = "Container output handling mode: ${COMPLETION-CANDIDATES} " + DEFAULT_VALUE, defaultValue = "PRINT")
    public DockerContainer.OutputMode output;

    @Option(names = "--launcher-bin", paramLabel = "<launcher bin>", description = "Launcher bin path (used to display run commands)", defaultValue = "${launcher.bin}", hidden = true)
    public String launcherBin;

    @Option(names = "--trino-jdk-version", paramLabel = "<trino-jdk-version>", description = "JDK to use for running Trino: ${COMPLETION-CANDIDATES} " + DEFAULT_VALUE, defaultValue = "ZULU_17")
    public SupportedTrinoJdk jdkVersion = SupportedTrinoJdk.ZULU_17;

    @Option(names = "--bind", description = "Bind exposed container ports to host ports, possible values: " + BIND_ON_HOST + ", " + DO_NOT_BIND + ", [port base number] " + DEFAULT_VALUE, defaultValue = BIND_ON_HOST, arity = "0..1", fallbackValue = BIND_ON_HOST)
    public void setBindOnHost(String value)
    {
        switch (value.toLowerCase(ENGLISH)) {
            case BIND_ON_HOST:
                this.bindPorts = true;
                break;
            case DO_NOT_BIND:
                this.bindPorts = false;
                break;
            default:
                try {
                    this.bindPortsBase = Integer.parseInt(value);
                    this.bindPorts = true;
                    checkArgument(this.bindPortsBase > 0, "Port bind base must be a positive integer");
                }
                catch (Exception e) {
                    throw new CommandLine.ParameterException(spec.commandLine(), "Port bind base is invalid", e);
                }
        }
    }

    public static EnvironmentOptions empty()
    {
        return new EnvironmentOptions();
    }
}
