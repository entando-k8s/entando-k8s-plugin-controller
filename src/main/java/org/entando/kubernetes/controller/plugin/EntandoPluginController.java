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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.StartupEvent;
import java.util.Optional;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.container.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.spi.result.DatabaseServiceResult;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.client.SimpleKeycloakClient;
import org.entando.kubernetes.controller.support.command.IngressingDeployCommand;
import org.entando.kubernetes.controller.support.controller.AbstractDbAwareController;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginSpec;

public class EntandoPluginController extends AbstractDbAwareController<EntandoPluginSpec, EntandoPlugin> {

    @Inject
    public EntandoPluginController(KubernetesClient kubernetesClient) {
        super(kubernetesClient);
    }

    public EntandoPluginController(SimpleK8SClient<?> k8sClient, SimpleKeycloakClient keycloakClient) {
        super(k8sClient, keycloakClient);
    }

    public EntandoPluginController(KubernetesClient kubernetesClient, boolean exitAutomatically) {
        super(kubernetesClient, exitAutomatically);
    }

    public void onStartup(@Observes StartupEvent event) {
        super.processCommand();
    }

    @Override
    protected void synchronizeDeploymentState(EntandoPlugin newEntandoPlugin) {
        DatabaseServiceResult databaseServiceResult = null;
        DbmsVendor dbmsVendor = newEntandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE);
        if (dbmsVendor != DbmsVendor.NONE && dbmsVendor != DbmsVendor.EMBEDDED) {
            databaseServiceResult = prepareDatabaseService(newEntandoPlugin, dbmsVendor);
        }
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources().findKeycloak(newEntandoPlugin);
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        IngressingDeployCommand<EntandoPluginDeploymentResult, EntandoPluginSpec> deployPluginServerCommand = new IngressingDeployCommand<>(
                new EntandoPluginServerDeployable(databaseServiceResult, keycloakConnectionConfig, newEntandoPlugin));
        EntandoPluginDeploymentResult result = deployPluginServerCommand.execute(k8sClient, Optional.of(keycloakClient));
        k8sClient.entandoResources().updateStatus(newEntandoPlugin, result.getStatus());
    }
}
