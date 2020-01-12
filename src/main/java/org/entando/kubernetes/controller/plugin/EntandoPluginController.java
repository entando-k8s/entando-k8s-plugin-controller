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
import org.entando.kubernetes.model.EntandoDeploymentPhase;
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
    protected void processAddition(EntandoPlugin newEntandoPlugin) {
        k8sClient.entandoResources().updatePhase(newEntandoPlugin, EntandoDeploymentPhase.STARTED);
        DatabaseServiceResult databaseServiceResult = prepareDatabaseService(newEntandoPlugin, newEntandoPlugin.getSpec().getDbms(), "db");
        KeycloakConnectionConfig keycloakConnectionConfig = k8sClient.entandoResources().findKeycloak(newEntandoPlugin);
        keycloakClient.login(keycloakConnectionConfig.getBaseUrl(), keycloakConnectionConfig.getUsername(),
                keycloakConnectionConfig.getPassword());
        keycloakClient.ensureRealm(KubeUtils.ENTANDO_KEYCLOAK_REALM);
        DeployCommand<ServiceDeploymentResult> deployPluginServerCommand = new DeployCommand<>(
                new EntandoPluginServerDeployable(databaseServiceResult, keycloakConnectionConfig, newEntandoPlugin));
        deployPluginServerCommand.execute(k8sClient, Optional.of(keycloakClient));
        k8sClient.entandoResources().updateStatus(newEntandoPlugin, deployPluginServerCommand.getStatus());
    }
}
