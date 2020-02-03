package org.entando.kubernetes.controller.plugin.interprocesstests;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.TlsHelper;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.integrationtest.support.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.FluentIntegrationTesting;
import org.entando.kubernetes.controller.integrationtest.support.HttpTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.K8SIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.plugin.EntandoPluginController;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public abstract class AddEntandoPluginBaseIT implements FluentIntegrationTesting {

    protected static final DbmsImageVendor DBMS = DbmsImageVendor.POSTGRESQL;
    protected String pluginHostName;
    protected K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();

    private static void registerListeners(K8SIntegrationTestHelper helper) {
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            helper.entandoPlugins().listenAndRespondWithImageVersionUnderTest(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE);
        } else {
            EntandoPluginController controller = new EntandoPluginController(helper.getClient(), false);
            helper.entandoPlugins()
                    .listenAndRespondWithStartupEvent(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE, controller::onStartup);
        }
        helper.keycloak()
                .listenAndRespondWithLatestImage(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE);
    }

    @BeforeEach
    public void cleanup() {
        K8SIntegrationTestHelper helper = this.helper;
        helper.setTextFixture(
                deleteAll(EntandoDatabaseService.class).fromNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                        .deleteAll(EntandoKeycloakServer.class).fromNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE)
        );
        await().atMost(2, TimeUnit.MINUTES).ignoreExceptions().pollInterval(10, TimeUnit.SECONDS).until(this::killPgPod);
        registerListeners(helper);
        //Determine best guess hostnames for the Entando DE App Ingress
        pluginHostName = EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "." + helper.getDomainSuffix();
    }

    private boolean killPgPod() {
        PodResource<Pod, DoneablePod> resource = helper.getClient().pods()
                .inNamespace(KeycloakIntegrationTestHelper.KEYCLOAK_NAMESPACE).withName("pg-test");
        if (resource.fromServer().get() == null) {
            return true;
        }
        resource.delete();
        return false;
    }

    public void createAndWaitForPlugin(EntandoPlugin plugin, boolean isDbEmbedded) {
        helper.ensureKeycloak();
        helper.clusterInfrastructure().ensureInfrastructureSecret();
        String name = plugin.getMetadata().getName();
        helper.keycloak().deleteKeycloakClients(name + "-" + KubeUtils.DEFAULT_SERVER_QUALIFIER, name + "-sidecar");
        helper.entandoPlugins().createAndWaitForPlugin(plugin, isDbEmbedded);
    }

    @AfterEach
    public void afterwards() {

        helper.afterTest();
    }

    protected void verifyPluginDatabasePreparation() {
        Pod pod = helper.getClient().pods().inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withLabel(KubeUtils.DB_JOB_LABEL_NAME, EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-db-preparation-job")
                .list().getItems().get(0);
        assertThat(theInitContainerNamed(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-plugindb-schema-creation-job").on(pod)
                        .getImage(),
                containsString("entando-k8s-dbjob"));
        pod.getStatus().getInitContainerStatuses()
                .forEach(containerStatus -> assertThat(containerStatus.getState().getTerminated().getExitCode(), is(0)));
    }

    protected void verifyPluginServerDeployment() {
        Deployment serverDeployment = helper.getClient().apps().deployments()
                .inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-server-deployment").fromServer().get();
        assertThat(thePortNamed("server-port")
                .on(theContainerNamed("server-container").on(serverDeployment))
                .getContainerPort(), is(8081));
        Service service = helper.getClient().services().inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-server-service").fromServer().get();
        assertThat(thePortNamed("server-port").on(service).getPort(), is(8081));
        assertTrue(serverDeployment.getStatus().getReadyReplicas() >= 1);
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> HttpTestHelper.read(TlsHelper.getDefaultProtocol() + "://" + pluginHostName + "/avatarPlugin/index.html")
                        .contains("JHipster microservice homepage"));
        assertTrue(helper.entandoPlugins().getOperations()
                .inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME)
                .fromServer().get().getStatus()
                .forServerQualifiedBy("server").isPresent());
        await().atMost(10, TimeUnit.SECONDS).until(() -> Arrays.asList(403, 401)
                .contains(HttpTestHelper.getStatus(TlsHelper.getDefaultProtocol() + "://" + pluginHostName + "/avatarPlugin/api/widgets")));
    }

}
