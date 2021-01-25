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

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.DockerImageInfo;
import org.entando.kubernetes.controller.database.DatabaseSchemaConnectionInfo;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.spi.ConfigurableResourceContainer;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.DefaultDockerImageInfo;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.SpringBootDeployableContainer;
import org.entando.kubernetes.model.EntandoIngressingDeploymentSpec;
import org.entando.kubernetes.model.KeycloakAwareSpec;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;

public class EntandoPluginDeployableContainer implements PersistentVolumeAware, SpringBootDeployableContainer, ParameterizableContainer,
        ConfigurableResourceContainer {

    public static final String PLUGINDB = "plugindb";
    private final EntandoPlugin entandoPlugin;
    private final KeycloakConnectionConfig keycloakConnectionConfig;
    private List<DatabaseSchemaConnectionInfo> databaseSchemaConnectionInfo;

    public EntandoPluginDeployableContainer(EntandoPlugin entandoPlugin, KeycloakConnectionConfig keycloakConnectionConfig,
            DatabaseServiceResult databaseServiceResult) {
        this.entandoPlugin = entandoPlugin;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
        this.databaseSchemaConnectionInfo = Optional.ofNullable(databaseServiceResult)
                .map(databaseServiceResult1 -> DbAware
                        .buildDatabaseSchemaConnectionInfo(entandoPlugin, databaseServiceResult, Collections.singletonList(PLUGINDB)))
                .orElse(Collections.emptyList());

    }

    @Override
    public Optional<DatabaseSchemaConnectionInfo> getDatabaseSchema() {
        return databaseSchemaConnectionInfo.stream().findFirst();
    }

    @Override
    public int getCpuLimitMillicores() {
        return 1000;
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 1024;
    }

    @Override
    public List<String> getNamesOfSecretsToMount() {
        return entandoPlugin.getSpec().getConnectionConfigNames();
    }

    @Override
    public DockerImageInfo getDockerImageInfo() {
        return new DefaultDockerImageInfo(entandoPlugin.getSpec().getImage());
    }

    @Override
    public String getNameQualifier() {
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
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
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("PORT", "8081", null));
        vars.add(new EnvVar("SPRING_PROFILES_ACTIVE", "default,prod", null));
        vars.add(new EnvVar("ENTANDO_WIDGETS_FOLDER", "/app/resources/widgets", null));
        vars.add(new EnvVar("ENTANDO_CONNECTIONS_ROOT", DeployableContainer.ENTANDO_SECRET_MOUNTS_ROOT, null));
        vars.add(new EnvVar("ENTANDO_PLUGIN_SECURITY_LEVEL",
                entandoPlugin.getSpec().getSecurityLevel().orElse(PluginSecurityLevel.STRICT).name(), null));
        vars.add(new EnvVar("PLUGIN_SIDECAR_PORT", "8084", null));
        return vars;
    }

    public KeycloakConnectionConfig getKeycloakConnectionConfig() {
        return keycloakConnectionConfig;
    }

    @Override
    public KeycloakClientConfig getKeycloakClientConfig() {
        return new KeycloakClientConfig(determineRealm(),
                entandoPlugin.getMetadata().getName() + "-" + getNameQualifier(),
                entandoPlugin.getMetadata().getName(), entandoPlugin.getSpec().getRoles(),
                entandoPlugin.getSpec().getPermissions())
                .withRole(KubeUtils.ENTANDO_APP_ROLE)
                .withPermission(EntandoPluginSidecarDeployableContainer.keycloakClientIdOf(entandoPlugin),
                        EntandoPluginSidecarDeployableContainer.REQUIRED_ROLE);
    }

    @Override
    public KeycloakAwareSpec getKeycloakAwareSpec() {
        return entandoPlugin.getSpec();
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath() + entandoPlugin.getSpec().getHealthCheckPath());
    }

    @Override
    public String getWebContextPath() {
        return entandoPlugin.getSpec().getIngressPath();
    }

    @Override
    public EntandoIngressingDeploymentSpec getCustomResourceSpec() {
        return getKeycloakAwareSpec();
    }

    @Override
    public List<DatabaseSchemaConnectionInfo> getSchemaConnectionInfo() {
        return this.databaseSchemaConnectionInfo;
    }
}
