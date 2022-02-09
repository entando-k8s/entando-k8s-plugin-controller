package org.entando.kubernetes.controller.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.capability.SerializingCapabilityProvider;
import org.entando.kubernetes.controller.spi.client.impl.DefaultKubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.SerializingDeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;
import org.entando.kubernetes.controller.support.client.impl.DefaultKeycloakClient;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.command.InProcessCommandStream;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("manual")})
class ManualRunTest implements FluentIntegrationTesting {

    private static final String TEST_NAMESPACE =
            EntandoOperatorTestConfig.calculateNameSpace("test-entando-k8s-plugin-controller");
    public static final String TEST_PLUGIN_NAME = EntandoOperatorTestConfig.calculateName("my-test-plugin");
    private static final String ENTANDO_RESOURCE_ACTION = "entando.resource.action";
    private static final String TEST_CONTROLLER_POD = "test-controller-pod";

    public KubernetesClient newKubernetesClient() {
        ConfigBuilder configBuilder = new ConfigBuilder()
                .withTrustCerts(true)
                .withRequestTimeout(30000)
                .withConnectionTimeout(30000);
        configBuilder = configBuilder.withNamespace(TEST_NAMESPACE);
        return new DefaultKubernetesClient(configBuilder.build());
    }

    @Test
    void testRun() {
        var client = newKubernetesClient();
        new DefaultKubernetesClientForControllers(client).prepareConfig();
        var simpleClient = new DefaultSimpleK8SClient(client);
        EntandoCustomResource entandoPlugin = buildAndApplyEntandyPluginCR(simpleClient);
        assertThat(entandoPlugin.getMetadata().getName()).isNotNull();
        createTrustStoreSecret(client);
        runControllerAgainstCustomResource(entandoPlugin, simpleClient);
    }

    private EntandoCustomResource buildAndApplyEntandyPluginCR(DefaultSimpleK8SClient simpleClient) {
        final String testHostnameSuffix = EntandoOperatorConfig.getDefaultRoutingSuffix().orElseThrow();
        final String ingressHostName = TEST_PLUGIN_NAME + "-" + TEST_NAMESPACE + "." + testHostnameSuffix;

        return simpleClient.entandoResources().createOrPatchEntandoResource(
                new EntandoPluginBuilder()
                        .withNewMetadata()
                        .withNamespace(TEST_NAMESPACE)
                        .withName(TEST_PLUGIN_NAME)
                        .endMetadata()
                        .withNewSpec()
                        .withIngressHostName(ingressHostName)
                        .withImage("entando/entando-avatar-plugin:6.0.5")
                        .withHealthCheckPath("/management/health")
                        .withIngressPath("/avatarPlugin")
                        .withDbms(DbmsVendor.EMBEDDED)
                        .endSpec()
                        .build()
        );
    }

    void runControllerAgainstCustomResource(
            EntandoCustomResource entandoCustomResource,
            DefaultSimpleK8SClient simpleK8SClient) {
        try {
            setEnvironment(entandoCustomResource);
            simpleK8SClient.entandoResources().createOrPatchEntandoResource(entandoCustomResource);
            runController(simpleK8SClient);
        } catch (RuntimeException e) {
            Logger.getLogger(getClass().getName()).log(Level.WARNING, e, e::getMessage);
            throw e;
        }
    }

    private void createTrustStoreSecret(KubernetesClient client) {
        final Secret secret = client.secrets().inNamespace("jx").withName("entando-jx-common-secret").get();
        String crt = decodeData(secret, "keycloak.server.ca-cert");
        if (!crt.isEmpty()) {
            TrustStoreHelper.trustCertificateAuthoritiesIn(
                    new SecretBuilder().addToData("test-keycloak-server-ca-cert",
                            Base64.getEncoder().encodeToString(crt.getBytes(StandardCharsets.UTF_8))).build());
        }

        client.secrets().inNamespace(TEST_NAMESPACE).createOrReplace(
                TrustStoreHelper.newTrustStoreSecret(
                        client.secrets().inNamespace(TEST_NAMESPACE).withName("test-ca-secret").get()
                )
        );
    }

    private String decodeData(Secret secret, String o) {
        return new String(Base64.getDecoder().decode(secret.getData().get(o)), StandardCharsets.UTF_8);
    }

    private void setEnvironment(EntandoCustomResource cr) {
        System.setProperty(ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(ENTANDO_RESOURCE_NAME.getJvmSystemProperty(), cr.getMetadata().getName());
        System.setProperty(ENTANDO_RESOURCE_NAMESPACE.getJvmSystemProperty(), cr.getMetadata().getNamespace());
        System.setProperty(ENTANDO_RESOURCE_KIND.getJvmSystemProperty(), cr.getKind());
        System.setProperty(ENTANDO_CONTROLLER_POD_NAME.getJvmSystemProperty(), TEST_CONTROLLER_POD);
    }

    public void runController(DefaultSimpleK8SClient simpleK8SClient) {
        //~
        var resourceClient = simpleK8SClient.entandoResources();
        var keycloakClient = new DefaultKeycloakClient();
        var commandStream = new InProcessCommandStream(simpleK8SClient, keycloakClient);
        var deploymentProcessor = new SerializingDeploymentProcessor(resourceClient, commandStream);
        var capabilityProvider = new SerializingCapabilityProvider(resourceClient, commandStream);

        new EntandoPluginController(resourceClient, deploymentProcessor, capabilityProvider).run();
    }
}
