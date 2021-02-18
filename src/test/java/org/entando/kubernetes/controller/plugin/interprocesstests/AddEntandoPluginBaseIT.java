/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.plugin.interprocesstests;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.client.EntandoOperatorTestConfig;
import org.entando.kubernetes.client.EntandoOperatorTestConfig.TestTarget;
import org.entando.kubernetes.client.integrationtesthelpers.FluentIntegrationTesting;
import org.entando.kubernetes.client.integrationtesthelpers.HttpTestHelper;
import org.entando.kubernetes.controller.plugin.EntandoPluginController;
import org.entando.kubernetes.controller.spi.common.DbmsDockerVendorStrategy;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.support.client.InfrastructureConfig;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.ResourceReference;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.test.common.CommonLabels;
import org.entando.kubernetes.test.e2etest.helpers.EntandoPluginE2ETestHelper;
import org.entando.kubernetes.test.e2etest.helpers.K8SIntegrationTestHelper;
import org.entando.kubernetes.test.e2etest.helpers.KeycloakE2ETestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class AddEntandoPluginBaseIT implements FluentIntegrationTesting, CommonLabels {

    public static final String CLUSTER_INFRASTRUCTURE_NAMESPACE = EntandoOperatorTestConfig
            .calculateNameSpace("entando-infra-namespace");
    public static final String CLUSTER_INFRASTRUCTURE_NAME = EntandoOperatorTestConfig.calculateName("eti");

    protected static final DbmsVendor DBMS = DbmsVendor.POSTGRESQL;
    protected static final DbmsDockerVendorStrategy DBMS_STRATEGY = DbmsDockerVendorStrategy
            .forVendor(DBMS, EntandoOperatorSpiConfig.getComplianceMode());
    protected String pluginHostName;
    protected K8SIntegrationTestHelper helper = new K8SIntegrationTestHelper();

    @BeforeEach
    void cleanup() {
        this.helper.setTextFixture(
                deleteAll(EntandoDatabaseService.class).fromNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE)
                        .deleteAll(EntandoPlugin.class).fromNamespace(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE)
        );
        this.helper.externalDatabases().deletePgTestPod(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE);
        this.helper.keycloak().prepareDefaultKeycloakSecretAndConfigMap();
        this.helper.keycloak().deleteRealm(KeycloakE2ETestHelper.KEYCLOAK_REALM);
        if (EntandoOperatorTestConfig.getTestTarget() == TestTarget.K8S) {
            this.helper.entandoPlugins()
                    .listenAndRespondWithImageVersionUnderTest(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE);
        } else {
            EntandoPluginController controller = new EntandoPluginController(this.helper.getClient(), false);
            this.helper.entandoPlugins()
                    .listenAndRespondWithStartupEvent(EntandoPluginE2ETestHelper.TEST_PLUGIN_NAMESPACE, controller::onStartup);
        }
        //Determine best guess hostnames for the Entando DE App Ingress
        pluginHostName = EntandoPluginE2ETestHelper.TEST_PLUGIN_NAME + "." + this.helper.getDomainSuffix();
    }

    void createAndWaitForPlugin(EntandoPlugin plugin, boolean isContainerizedDb) {
        ensureInfrastructureConnectionConfig();
        String name = plugin.getMetadata().getName();
        helper.keycloak().deleteKeycloakClients(plugin, name + "-" + NameUtils.DEFAULT_SERVER_QUALIFIER, name + "-sidecar");
        helper.entandoPlugins().createAndWaitForPlugin(plugin, isContainerizedDb);
    }

    //TODO get rid of this once we deploy K8S with the operator
    public void ensureInfrastructureConnectionConfig() {
        helper.entandoPlugins().loadDefaultCapabilitiesConfigMap()
                .addToData(InfrastructureConfig.DEFAULT_CLUSTER_INFRASTRUCTURE_NAMESPACE_KEY, CLUSTER_INFRASTRUCTURE_NAMESPACE)
                .addToData(InfrastructureConfig.DEFAULT_CLUSTER_INFRASTRUCTURE_NAME_KEY, CLUSTER_INFRASTRUCTURE_NAME)
                .done();
        ResourceReference infrastructureToUse = new ResourceReference(CLUSTER_INFRASTRUCTURE_NAMESPACE, CLUSTER_INFRASTRUCTURE_NAME);
        delete(helper.getClient().configMaps())
                .named(InfrastructureConfig.connectionConfigMapNameFor(infrastructureToUse))
                .fromNamespace(CLUSTER_INFRASTRUCTURE_NAMESPACE)
                .waitingAtMost(20, SECONDS);
        String hostName = "http://" + CLUSTER_INFRASTRUCTURE_NAME + "." + helper.getDomainSuffix();
        helper.getClient().configMaps()
                .inNamespace(CLUSTER_INFRASTRUCTURE_NAMESPACE)
                .createNew()
                .withNewMetadata()
                .withName(InfrastructureConfig.connectionConfigMapNameFor(infrastructureToUse))
                .endMetadata()
                .addToData(InfrastructureConfig.ENTANDO_K8S_SERVICE_CLIENT_ID_KEY, CLUSTER_INFRASTRUCTURE_NAME + "-k8s-svc")
                .addToData(InfrastructureConfig.ENTANDO_K8S_SERVICE_INTERNAL_URL_KEY, hostName + "/k8s")
                .addToData(InfrastructureConfig.ENTANDO_K8S_SERVICE_EXTERNAL_URL_KEY, hostName + "/k8s")
                .done();
    }

    @AfterEach
    void afterwards() {
        helper.afterTest();
    }

    protected void verifyPluginDatabasePreparation(EntandoPlugin plugin) {
        Pod pod = helper.getClient().pods().inNamespace(plugin.getMetadata().getNamespace())
                .withLabels(dbPreparationJobLabels(plugin, NameUtils.DEFAULT_SERVER_QUALIFIER))
                .list().getItems().get(0);
        assertThat(theInitContainerNamed(plugin.getMetadata().getName() + "-plugindb-schema-creation-job").on(pod)
                        .getImage(),
                containsString("entando-k8s-dbjob"));
        pod.getStatus().getInitContainerStatuses()
                .forEach(containerStatus -> assertThat(containerStatus.getState().getTerminated().getExitCode(), is(0)));
    }

    protected void verifyPluginServerDeployment(EntandoPlugin plugin) {
        Deployment serverDeployment = helper.getClient().apps().deployments()
                .inNamespace(plugin.getMetadata().getNamespace())
                .withName(plugin.getMetadata().getName() + "-server-deployment").fromServer().get();
        assertThat(thePortNamed("server-port")
                .on(theContainerNamed("server-container").on(serverDeployment))
                .getContainerPort(), is(8081));
        Service service = helper.getClient().services().inNamespace(plugin.getMetadata().getNamespace())
                .withName(plugin.getMetadata().getName() + "-server-service").fromServer().get();
        assertThat(thePortNamed("server-port").on(service).getPort(), is(8081));
        assertTrue(serverDeployment.getStatus().getReadyReplicas() >= 1);
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> HttpTestHelper.read(HttpTestHelper.getDefaultProtocol() + "://" + pluginHostName + "/avatarPlugin/index.html")
                        .contains("JHipster microservice homepage"));
        assertTrue(helper.entandoPlugins().getOperations()
                .inNamespace(plugin.getMetadata().getNamespace())
                .withName(plugin.getMetadata().getName())
                .fromServer().get().getStatus()
                .forServerQualifiedBy("server").isPresent());
        await().atMost(10, TimeUnit.SECONDS).until(() -> Arrays.asList(403, 401)
                .contains(HttpTestHelper
                        .getStatus(HttpTestHelper.getDefaultProtocol() + "://" + pluginHostName + "/avatarPlugin/api/widgets")));
    }

}
