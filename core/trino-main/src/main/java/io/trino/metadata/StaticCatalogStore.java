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
package io.trino.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import io.airlift.log.Logger;
import io.trino.connector.ConnectorManager;

import javax.inject.Inject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.configuration.ConfigurationLoader.loadPropertiesFrom;

public class StaticCatalogStore
{
    private static final Logger log = Logger.get(StaticCatalogStore.class);
    private final ConnectorManager connectorManager;
    private final File catalogConfigurationDir;
    private final Set<String> disabledCatalogs;
    private final AtomicBoolean catalogsLoading = new AtomicBoolean();

    @Inject
    public StaticCatalogStore(ConnectorManager connectorManager, StaticCatalogStoreConfig config)
    {
        this(connectorManager,
                config.getCatalogConfigurationDir(),
                firstNonNull(config.getDisabledCatalogs(), ImmutableList.of()));
    }

    public StaticCatalogStore(ConnectorManager connectorManager, File catalogConfigurationDir, List<String> disabledCatalogs)
    {
        this.connectorManager = connectorManager;
        this.catalogConfigurationDir = catalogConfigurationDir;
        this.disabledCatalogs = ImmutableSet.copyOf(disabledCatalogs);
    }

    public void loadCatalogs()
            throws Exception
    {
        if (!catalogsLoading.compareAndSet(false, true)) {
            return;
        }

        for (File file : listFiles(catalogConfigurationDir)) {
            if (file.isFile() && file.getName().endsWith(".properties")) {
                loadCatalog(file);
            }
        }
    }

    private void loadCatalog(File file)
            throws Exception
    {
        String catalogName = Files.getNameWithoutExtension(file.getName());
        if (disabledCatalogs.contains(catalogName)) {
            log.info("Skipping disabled catalog %s", catalogName);
            return;
        }

        log.info("-- Loading catalog %s --", file);
        Map<String, String> properties = new HashMap<>(loadPropertiesFrom(file.getPath()));

        String connectorName = properties.remove("connector.name");
        checkState(connectorName != null, "Catalog configuration %s does not contain connector.name", file.getAbsoluteFile());

        connectorManager.createCatalog(catalogName, connectorName, ImmutableMap.copyOf(properties));
        log.info("-- Added catalog %s using connector %s --", catalogName, connectorName);
    }

    private static List<File> listFiles(File installedPluginsDir)
    {
        if (installedPluginsDir != null && installedPluginsDir.isDirectory()) {
            File[] files = installedPluginsDir.listFiles();
            if (files != null) {
                return ImmutableList.copyOf(files);
            }
        }
        return ImmutableList.of();
    }
}
