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
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.DeployCommand;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoPluginController extends AbstractDbAwareController<EntandoPlugin> {

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
        if (dbmsVendor != DbmsVendor.NONE) {
            databaseServiceResult = prepareDatabaseService(newEntandoPlugin, dbmsVendor, "db");
        }
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources().findKeycloak(newEntandoPlugin);
        keycloakClient.login(keycloakConnectionConfig.determineBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.ensureRealm(KubeUtils.ENTANDO_KEYCLOAK_REALM);
        DeployCommand<ServiceDeploymentResult> deployPluginServerCommand = new DeployCommand<>(
                new EntandoPluginServerDeployable(databaseServiceResult, keycloakConnectionConfig, newEntandoPlugin));
        deployPluginServerCommand.execute(k8sClient, Optional.of(keycloakClient));
    }
}
