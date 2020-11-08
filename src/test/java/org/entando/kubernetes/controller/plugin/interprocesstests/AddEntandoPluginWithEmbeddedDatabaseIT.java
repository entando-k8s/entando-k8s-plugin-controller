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

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test"), @Tag("post-deployment")})
class AddEntandoPluginWithEmbeddedDatabaseIT extends AddEntandoPluginBaseIT {

    @Test
    void testCreate() {
        EntandoPlugin plugin = new EntandoPluginBuilder()
                .withNewSpec()
                .withImage("entando/entando-avatar-plugin")
                .withNewKeycloakToUse()
                .withRealm(KeycloakIntegrationTestHelper.KEYCLOAK_REALM)
                .endKeycloakToUse()
                .withDbms(DBMS)
                .withReplicas(1)
                .withIngressHostName(pluginHostName)
                .withHealthCheckPath("/management/health")
                .withIngressPath("/avatarPlugin")
                .withSecurityLevel(PluginSecurityLevel.STRICT)
                .addNewConnectionConfigName(EntandoPluginIntegrationTestHelper.PAM_CONNECTION_CONFIG)
                .endSpec()
                .build();
        plugin.getMetadata().setName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME);
        SampleWriter.writeSample(plugin, format("plugin-with-embedded-%s-db", DBMS.toValue()));
        createAndWaitForPlugin(plugin, true);
        verifyPluginDbDeployment();
        verifyPluginDatabasePreparation();
        verifyPluginServerDeployment();
    }

    private void verifyPluginDbDeployment() {
        Deployment deployment = helper.getClient().apps().deployments()
                .inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-db-deployment").fromServer().get();
        assertThat(thePortNamed(DB_PORT)
                        .on(theContainerNamed("db-container").on(deployment))
                        .getContainerPort(),
                is(DBMS_STRATEGY.getPort()));
        Service dbService = helper.getClient().services().inNamespace(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE)
                .withName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME + "-db-service").fromServer().get();
        assertThat(thePortNamed(DB_PORT).on(dbService).getPort(), is(DBMS_STRATEGY.getPort()));
        await().atMost(20, TimeUnit.SECONDS).ignoreExceptions().until(() -> deployment.getStatus().getReadyReplicas() >= 1);
    }
}
