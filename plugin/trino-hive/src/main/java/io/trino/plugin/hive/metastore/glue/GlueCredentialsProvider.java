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
package io.trino.plugin.hive.metastore.glue;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.STSAssumeRoleSessionCredentialsProvider;

import javax.inject.Inject;
import javax.inject.Provider;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class GlueCredentialsProvider
        implements Provider<AWSCredentialsProvider>
{
    private final AWSCredentialsProvider credentialsProvider;

    @Inject
    public GlueCredentialsProvider(GlueHiveMetastoreConfig config)
    {
        requireNonNull(config, "config is null");
        if (config.getAwsCredentialsProvider().isPresent()) {
            this.credentialsProvider = getCustomAWSCredentialsProvider(config.getAwsCredentialsProvider().get());
        }
        else {
            AWSCredentialsProvider provider;
            if (config.getAwsAccessKey().isPresent() && config.getAwsSecretKey().isPresent()) {
                provider = new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(config.getAwsAccessKey().get(), config.getAwsSecretKey().get()));
            }
            else {
                provider = DefaultAWSCredentialsProviderChain.getInstance();
            }
            if (config.getIamRole().isPresent()) {
                provider = new STSAssumeRoleSessionCredentialsProvider
                        .Builder(config.getIamRole().get(), "trino-session")
                        .withExternalId(config.getExternalId().orElse(null))
                        .withLongLivedCredentialsProvider(provider)
                        .build();
            }
            this.credentialsProvider = provider;
        }
    }

    @Override
    public AWSCredentialsProvider get()
    {
        return credentialsProvider;
    }

    private static AWSCredentialsProvider getCustomAWSCredentialsProvider(String providerClass)
    {
        try {
            Object instance = Class.forName(providerClass).getConstructor().newInstance();
            if (!(instance instanceof AWSCredentialsProvider)) {
                throw new RuntimeException("Invalid credentials provider class: " + instance.getClass().getName());
            }
            return (AWSCredentialsProvider) instance;
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException(format("Error creating an instance of %s", providerClass), e);
        }
    }
}
