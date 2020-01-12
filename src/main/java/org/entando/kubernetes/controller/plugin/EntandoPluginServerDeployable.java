package org.entando.kubernetes.controller.plugin;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.extensions.Ingress;
import java.util.Arrays;
import java.util.List;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.ServiceDeploymentResult;
import org.entando.kubernetes.controller.database.DatabaseServiceResult;
import org.entando.kubernetes.controller.spi.DbAwareDeployable;
import org.entando.kubernetes.controller.spi.DeployableContainer;
import org.entando.kubernetes.controller.spi.IngressingDeployable;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.plugin.EntandoPlugin;

public class EntandoPluginServerDeployable implements IngressingDeployable<ServiceDeploymentResult>, DbAwareDeployable {

    private final DatabaseServiceResult databaseServiceResult;
    private final EntandoPlugin entandoPlugin;
    private final List<DeployableContainer> containers;

    public EntandoPluginServerDeployable(DatabaseServiceResult databaseServiceResult,
            KeycloakConnectionConfig keycloakConnectionConfig, EntandoPlugin entandoPlugin) {
        this.databaseServiceResult = databaseServiceResult;
        this.entandoPlugin = entandoPlugin;
        //TODO make decision on which other containers to include based on the EntandoPlugin.spec
        this.containers = Arrays.asList(
                new EntandoPluginDeployableContainer(entandoPlugin, keycloakConnectionConfig),
                new EntandoPluginSidecarDeployableContainer(entandoPlugin, keycloakConnectionConfig));
    }

    @Override
    public String getServiceAccountName() {
        return "entando-plugin";
    }

    @Override
    public List<DeployableContainer> getContainers() {
        return containers;
    }

    @Override
    public String getIngressName() {
        return KubeUtils.standardIngressName(entandoPlugin);
    }

    @Override
    public String getIngressNamespace() {
        return entandoPlugin.getMetadata().getNamespace();
    }

    @Override
    public String getNameQualifier() {
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public EntandoCustomResource getCustomResource() {
        return entandoPlugin;
    }

    @Override
    public ServiceDeploymentResult createResult(Deployment deployment, Service service, Ingress ingress, Pod pod) {
        return new ServiceDeploymentResult(service, ingress);
    }

    @Override
    public DatabaseServiceResult getDatabaseServiceResult() {
        return databaseServiceResult;
    }
}
