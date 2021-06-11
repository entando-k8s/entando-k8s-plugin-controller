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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.KubernetesPermission;
import org.entando.kubernetes.controller.spi.container.ParameterizableContainer;
import org.entando.kubernetes.controller.spi.container.SsoAwareContainer;
import org.entando.kubernetes.controller.spi.container.SsoClientConfig;
import org.entando.kubernetes.controller.spi.container.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.container.TrustStoreAwareContainer;
import org.entando.kubernetes.model.common.KeycloakToUse;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoPluginSidecarDeployableContainer implements DeployableContainer, SsoAwareContainer, TrustStoreAwareContainer,
        ParameterizableContainer {

    public static final String REQUIRED_ROLE = "connection-config";
    private static final String ENTANDO_PLUGIN_SIDECAR_IMAGE = "entando/entando-plugin-sidecar";

    private final EntandoPlugin entandoPlugin;
    private final SsoConnectionInfo ssoConnectionInfo;

    public EntandoPluginSidecarDeployableContainer(EntandoPlugin entandoPlugin, SsoConnectionInfo ssoConnectionInfo) {
        this.entandoPlugin = entandoPlugin;
        this.ssoConnectionInfo = ssoConnectionInfo;
    }

    @Override
    public SsoConnectionInfo getSsoConnectionInfo() {
        return ssoConnectionInfo;
    }

    @Override
    public Optional<KeycloakToUse> getPreferredKeycloakToUse() {
        return entandoPlugin.getSpec().getKeycloakToUse();
    }

    public static String keycloakClientIdOf(EntandoPlugin entandoPlugin) {
        return entandoPlugin.getMetadata().getName() + "-" + "sidecar";
    }

    @Override
    public int getCpuLimitMillicores() {
        return 750;
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 768;
    }

    @Override
    public String determineImageToUse() {
        return ENTANDO_PLUGIN_SIDECAR_IMAGE;
    }

    @Override
    public String getNameQualifier() {
        return "sidecar";
    }

    @Override
    public int getPrimaryPort() {
        return 8084;
    }

    @Override
    public List<EnvVar> getEnvironmentVariables() {
        List<EnvVar> vars = new ArrayList<>();
        vars.add(new EnvVar("ENTANDO_PLUGIN_NAME", entandoPlugin.getMetadata().getName(), null));
        return vars;
    }

    @Override
    public SsoClientConfig getSsoClientConfig() {
        String clientId = keycloakClientIdOf(this.entandoPlugin);
        return new SsoClientConfig(getKeycloakRealmToUse(), clientId, clientId).withRole(REQUIRED_ROLE);
    }

    @Override
    public String getWebContextPath() {
        return "";
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of("/actuator/health");
    }

    @Override
    public List<KubernetesPermission> getKubernetesPermissions() {
        return Arrays.asList(new KubernetesPermission("entando.org", "entandoplugins", "get", "update"),
                new KubernetesPermission("", "secrets", "create", "get", "update", "delete"));
    }

    @Override
    public List<EnvVar> getEnvironmentVariableOverrides() {
        return entandoPlugin.getSpec().getEnvironmentVariables();
    }
}
