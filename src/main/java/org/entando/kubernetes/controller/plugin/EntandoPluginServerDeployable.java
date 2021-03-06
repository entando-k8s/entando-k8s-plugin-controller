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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.ArrayList;
import java.util.List;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.DeployableContainer;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.deployable.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.spibase.IngressingDeployableBase;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;

public class EntandoPluginServerDeployable
        implements IngressingDeployableBase<EntandoPluginDeploymentResult>, DbAwareDeployable<EntandoPluginDeploymentResult> {

    private final EntandoPlugin entandoPlugin;
    private final List<DeployableContainer> containers;

    public EntandoPluginServerDeployable(DatabaseServiceResult databaseServiceResult,
            KeycloakConnectionConfig keycloakConnectionConfig, EntandoPlugin entandoPlugin) {
        this.entandoPlugin = entandoPlugin;
        //TODO make decision on which other containers to include based on the EntandoPlugin.spec
        this.containers = new ArrayList<>();
        this.containers.add(new EntandoPluginDeployableContainer(entandoPlugin, keycloakConnectionConfig, databaseServiceResult));
        if (EntandoOperatorSpiConfig.getComplianceMode() != EntandoOperatorComplianceMode.REDHAT
                && entandoPlugin.getSpec().getSecurityLevel().orElse(PluginSecurityLevel.STRICT) == PluginSecurityLevel.LENIENT) {
            this.containers.add(new EntandoPluginSidecarDeployableContainer(entandoPlugin, keycloakConnectionConfig));
        }
    }

    @Override
    public int getReplicas() {
        return entandoPlugin.getSpec().getReplicas().orElse(1);
    }

    @Override
    public String getDefaultServiceAccountName() {
        return "entando-plugin";
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public String getIngressName() {
        return NameUtils.standardIngressName(entandoPlugin);
    }

    @Override
    public String getIngressNamespace() {
        return entandoPlugin.getMetadata().getNamespace();
    }

    @Override
    public String getNameQualifier() {
        return NameUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public EntandoPlugin getCustomResource() {
        return entandoPlugin;
    }

    @Override
    public EntandoPluginDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new EntandoPluginDeploymentResult(pod, service, ingress);
    }

    @Override
    public String getServiceAccountToUse() {
        return entandoPlugin.getSpec().getServiceAccountToUse().orElse(getDefaultServiceAccountName());
    }

}
