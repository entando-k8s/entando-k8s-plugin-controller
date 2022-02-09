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

import static io.qameta.allure.Allure.attachment;
import static io.qameta.allure.Allure.step;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.support.client.impl.EntandoOperatorTestConfig;
import org.entando.kubernetes.controller.support.client.impl.SupportProducer;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.support.client.impl.integrationtesthelpers.TestFixturePreparation;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.test.common.KeycloakTestCapabilityProvider;
import org.entando.kubernetes.test.e2etest.ControllerExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("smoke"), @Tag("allure"), @Tag("post-deployment"), @Tag("inter-process")})
@Feature(
        "As an Entando Operator users, I want to use a Docker container to process an EntandoPlugin so that I don't need to "
                + "know any of its implementation details to use it.")
class EntandoPluginSmokeTest implements FluentIntegrationTesting {

    private static final String TEST_NAMESPACE = EntandoOperatorTestConfig.calculateNameSpace("my-namespace");
    public static final String TEST_PLUGIN_NAME = EntandoOperatorTestConfig.calculateName("my-test-plugin");

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    private EntandoPlugin entandoPlugin;

    @Test
    @Description("Should successfully connect to newly deployed Entando Plugin")
    void testDeployment() {
        final String testHostnameSuffix = EntandoOperatorConfig.getDefaultRoutingSuffix()
                .orElseThrow(() -> new IllegalStateException("please provide ENTANDO_DEFAULT_ROUTING_SUFFIX"));
        final String testImageVersion = EntandoOperatorTestConfig.getVersionOfImageUnderTest()
                .orElseThrow(() -> new IllegalStateException("please provide ENTANDO_TEST_IMAGE_VERSION"));

        final String ingressHostName = TEST_PLUGIN_NAME + "-" + TEST_NAMESPACE + "." + testHostnameSuffix;
        final KubernetesClient client = new SupportProducer().getKubernetesClient();
        final DefaultSimpleK8SClient simpleClient = new DefaultSimpleK8SClient(client);
        step("Given I have a clean namespace", () -> {
            TestFixturePreparation.prepareTestFixture(client,
                    deleteAll(EntandoPlugin.class).fromNamespace(TEST_NAMESPACE));
        });
        step("And I have configured a ProvidedCapability for SSO in the namespace", () -> {
            System.setProperty(ENTANDO_CONTROLLER_POD_NAME.getJvmSystemProperty(), "test-kc-controller-pod");
            final var providedCapability =
                    new KeycloakTestCapabilityProvider(simpleClient, TEST_NAMESPACE).provideKeycloakCapability();
            attachment("SSO Capability", objectMapper.writeValueAsString(providedCapability));
        });
        step("And I have created an EntandoPlugin custom resource with an Embedded DBMS", () -> {
            trustExternalKeycloak(client);
            this.entandoPlugin = simpleClient.entandoResources()
                    .createOrPatchEntandoResource(
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
            attachment("Entando Plugin", objectMapper.writeValueAsString(this.entandoPlugin));
        });
        step("When I run the entando-k8s-plugin-controller container against the EntandoPlugin", () -> {
            ControllerExecutor executor = new ControllerExecutor(TEST_NAMESPACE, simpleClient,
                    r -> "entando-k8s-plugin-controller");

            executor.runControllerFor(Action.ADDED, entandoPlugin, testImageVersion);
        });

        step("Then I can successfully access the Plugin's health URL", () -> {
            final String strUrl =
                    HttpTestHelper.getDefaultProtocol() + "://" + ingressHostName
                            + "/avatarPlugin/management/health";
            await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().until(() -> HttpTestHelper.statusOk(strUrl));
            assertThat(HttpTestHelper.statusOk(strUrl)).isTrue();
        });
    }

    public static void trustExternalKeycloak(KubernetesClient client) {
        final Secret secret = client.secrets().inNamespace("jx").withName("entando-jx-common-secret").get();
        String crt = decodeData(secret, "keycloak.server.ca-cert");
        if (!crt.isEmpty()) {
            Secret tsCaSecret = new SecretBuilder()
                    .withType("opaque")
                    .withNewMetadata().withName("test-keycloak-server-ca-cert").endMetadata()
                    .withData(Collections.singletonMap("ca0.crt", encodeData(crt))).build();
            client.secrets().inNamespace(TEST_NAMESPACE).createOrReplace(tsCaSecret);

            Secret tsSecret = TrustStoreHelper.newTrustStoreSecret(tsCaSecret);
            TrustStoreHelper.trustCertificateAuthoritiesIn(tsCaSecret);

            client.secrets().inNamespace(TEST_NAMESPACE).createOrReplace(tsSecret);
        }
    }

    private static String encodeData(String crt) {
        return Base64.getEncoder().encodeToString(crt.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeData(Secret secret, String o) {
        return new String(Base64.getDecoder().decode(secret.getData().get(o)), StandardCharsets.UTF_8);
    }
}
