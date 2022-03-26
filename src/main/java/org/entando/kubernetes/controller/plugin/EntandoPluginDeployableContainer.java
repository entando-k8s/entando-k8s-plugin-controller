/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.plugin;

import static java.util.Optional.ofNullable;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase.lookupProperty;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.SecretUtils;
import org.entando.kubernetes.controller.spi.container.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.container.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DefaultDatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.DockerImageInfo;
import org.entando.kubernetes.controller.spi.container.KeycloakName;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.PersistentVolumeAwareContainer;
import org.entando.kubernetes.controller.spi.container.SpringBootDeployableContainer;
import org.entando.kubernetes.controller.spi.container.SsoAwareContainer;
import org.entando.kubernetes.controller.spi.deployable.SsoClientConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoResourceRequirements;
import org.entando.kubernetes.model.common.KeycloakAwareSpec;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;

public class EntandoPluginDeployableContainer implements PersistentVolumeAwareContainer,
        SpringBootDeployableContainer,
        ParameterizableContainer,
        ConfigurableResourceContainer, SsoAwareContainer {

    private final EntandoPlugin entandoPlugin;
    private final SsoConnectionInfo ssoConnectionInfo;
    private final List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfo;
    private final SsoClientConfig ssoClientConfig;

    /**
     * The configuration profile containing the customizations for the bundle deployment.
     */
    private final EntandoConfigurationProfile configurationProfile = new EntandoConfigurationProfile();

    public EntandoPluginDeployableContainer(EntandoPlugin entandoPlugin, String pluginDbmsSecretName,
            SsoConnectionInfo ssoConnectionInfo, DatabaseConnectionInfo databaseConnectionInfo,
            SsoClientConfig ssoClientConfig, String schemaNameOverride) {
        this.entandoPlugin = entandoPlugin;
        this.ssoConnectionInfo = ssoConnectionInfo;
        this.ssoClientConfig = ssoClientConfig;
        this.databaseSchemaConnectionInfo = ofNullable(databaseConnectionInfo)
                .map(dsr -> List.of(buildDatabaseSchemaConnectionInfo(entandoPlugin, databaseConnectionInfo,
                        pluginDbmsSecretName, schemaNameOverride)))
                .orElse(Collections.emptyList());
    }

    private static DatabaseSchemaConnectionInfo buildDatabaseSchemaConnectionInfo(
            EntandoCustomResource entandoBaseCustomResource,
            DatabaseConnectionInfo databaseConnectionInfo,
            String pluginDbmsSecretName, String schemaNameOverride) {
        //~
        final var dbmsVendor = databaseConnectionInfo.getVendor();
        final var pluginName = entandoBaseCustomResource.getMetadata().getName();

        String schemaName;

        //noinspection ReplaceNullCheck
        if (schemaNameOverride != null) {
            schemaName = schemaNameOverride;
        } else {
            schemaName = (dbmsVendor.schemaIsDatabase())
                    ? generateUsername(pluginName, dbmsVendor)
                    : generateDbName(pluginName, dbmsVendor);
        }

        final var secret = SecretUtils.generateSecret(entandoBaseCustomResource, pluginDbmsSecretName, schemaName);

        return new DefaultDatabaseSchemaConnectionInfo(databaseConnectionInfo, schemaName, secret);
    }

    private static String generateDbName(String baseName, DbmsVendorConfig dbmsVendor) {
        String res = NameUtils.snakeCaseOf(baseName);
        if (res.length() > dbmsVendor.getMaxDatabaseNameLength()) {
            res = res.substring(0, dbmsVendor.getMaxDatabaseNameLength() - 4) + "_" + NameUtils.randomNumeric(4);
            debugLogMaxSize("database name", baseName, dbmsVendor, res);
        }
        return res;
    }

    static String generateUsername(String baseName, DbmsVendorConfig dbmsVendor) {
        int maxLength = dbmsVendor.getMaxUsernameLength();
        String res = NameUtils.snakeCaseOf(baseName);
        if (res.length() > maxLength - 1) {
            int lenWithoutRandom = maxLength - (USERNAME_RANDOM_HASH_LENGTH + 1);
            res = res.substring(0, lenWithoutRandom);
            res += "_" + NameUtils.randomNumeric(USERNAME_RANDOM_HASH_LENGTH);
            debugLogMaxSize("database username", baseName, dbmsVendor, res);
        }
        return res;
    }

    private static void debugLogMaxSize(String type, String baseName, DbmsVendorConfig dbmsVendor, String res) {
        logger.debug("{} \"{}\" too long: {} supports max length of {}. Truncating to \"{}\"",
                type, baseName, dbmsVendor.getName(), dbmsVendor.getMaxUsernameLength(), res);
    }


    @Override
    public Optional<String> getStorageClass() {
        return ofNullable(this.entandoPlugin.getSpec().getStorageClass()
                .orElse(PersistentVolumeAwareContainer.super.getStorageClass().orElse(null)));
    }

    @Override
    public Optional<DatabaseSchemaConnectionInfo> getDatabaseSchema() {
        return databaseSchemaConnectionInfo.stream().findFirst();
    }

    @Override
    public int getCpuLimitMillicores() {
        return (int) getConfigProfile().getNumber("resources.limits.cpu", 1000);
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return (int) getConfigProfile().getNumber("resources.limits.memory", 1024);
    }

    @Override
    public Optional<DbmsVendor> getDbms() {
        return entandoPlugin.getSpec().getDbms();
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        return ssoClientConfig;
    }

    @Override
    public List<String> getNamesOfSecretsToMount() {
        return entandoPlugin.getSpec().getConnectionConfigNames();
    }

    @Override
    public DockerImageInfo getDockerImageInfo() {
        return new DockerImageInfo(entandoPlugin.getSpec().getImage());
    }

    @Override
    public String getNameQualifier() {
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public int getPrimaryPort() {
        return 8081;
    }

    @Override
    public String getVolumeMountPath() {
        return "/entando-data";
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        configurationProfile.loadForPlugin(entandoPlugin.getMetadata().getName());

        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("PORT", "8081", null));
        vars.add(new EnvVar("SPRING_PROFILES_ACTIVE", "default,prod", null));
        vars.add(new EnvVar("ENTANDO_WIDGETS_FOLDER", "/app/resources/widgets", null));
        vars.add(new EnvVar("ENTANDO_CONNECTIONS_ROOT", DeployableContainer.ENTANDO_SECRET_MOUNTS_ROOT, null));
        vars.add(new EnvVar("ENTANDO_PLUGIN_SECURITY_LEVEL",
                entandoPlugin.getSpec().getSecurityLevel().orElse(PluginSecurityLevel.STRICT).name(), null));
        vars.add(new EnvVar("PLUGIN_SIDECAR_PORT", "8084", null));
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND);
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE);
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME);
        propagateProperty(vars, EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME);
        return vars;
    }

    private EntandoConfigurationProfile getConfigProfile() {
        return configurationProfile;
    }

    @Override
    public List<EnvVar> getSsoVariables() {
        List<EnvVar> vars = SpringBootDeployableContainer.super.getSsoVariables();
        //TODO remove this once the plugins stop using it. Plugins should use the standard Spring variable
        // SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI
        vars.add(new EnvVar("KEYCLOAK_REALM", ssoClientConfig.getRealm(), null));
        ofNullable(getSsoConnectionInfo()).ifPresent(sso ->
                vars.add(new EnvVar("KEYCLOAK_AUTH_URL", sso.getExternalBaseUrl(), null)));
        String keycloakSecretName = KeycloakName.forTheClientSecret(ssoClientConfig);
        vars.add(new EnvVar("KEYCLOAK_CLIENT_SECRET", null,
                SecretUtils.secretKeyRef(keycloakSecretName, KeycloakName.CLIENT_SECRET_KEY)));
        vars.add(new EnvVar("KEYCLOAK_CLIENT_ID", null,
                SecretUtils.secretKeyRef(keycloakSecretName, KeycloakName.CLIENT_ID_KEY)));
        return vars;

    }

    private void propagateProperty(List<EnvVar> vars, EntandoOperatorSpiConfigProperty prop) {
        lookupProperty(prop).ifPresent(s -> vars.add(new EnvVar(prop.name(), s, null)));
    }

    @Override
    public SsoConnectionInfo getSsoConnectionInfo() {
        return this.ssoConnectionInfo;
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath() + entandoPlugin.getSpec().getHealthCheckPath());
    }

    @Override
    public String getWebContextPath() {
        return ofNullable(entandoPlugin.getSpec().getIngressPath()).orElse("/" + entandoPlugin.getMetadata().getName());
    }

    @Override
    public List<DatabaseSchemaConnectionInfo> getSchemaConnectionInfo() {
        return this.databaseSchemaConnectionInfo;
    }

    @Override
    public Optional<EntandoResourceRequirements> getResourceRequirementsOverride() {
        return getKeycloakAwareSpec().getResourceRequirements();
    }

    @Override
    public Optional<Integer> getMaximumStartupTimeSeconds() {
        return Optional.of(120);
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return getKeycloakAwareSpec().getEnvironmentVariables();
    }

    private KeycloakAwareSpec getKeycloakAwareSpec() {
        return entandoPlugin.getSpec();
    }
}
