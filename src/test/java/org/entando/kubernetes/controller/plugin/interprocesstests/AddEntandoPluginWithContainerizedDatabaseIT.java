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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.KeycloakIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorComplianceMode;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test"), @Tag("post-deployment")})
class AddEntandoPluginWithContainerizedDatabaseIT extends AddEntandoPluginBaseIT {

    @AfterEach
    void resetSystemProperties() {
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty());
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMAGE_PULL_SECRETS.getJvmSystemProperty());
    }

    @ParameterizedTest
    @EnumSource(value = EntandoOperatorComplianceMode.class, names = {"REDHAT", "COMMUNITY"})
    void testCreate(EntandoOperatorComplianceMode complianceMode) {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMAGE_PULL_SECRETS.getJvmSystemProperty(), "redhat-registry");

        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty(),
                complianceMode.name().toLowerCase(Locale.ROOT));
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
        verifyPluginDbDeployment(plugin);
        verifyPluginDatabasePreparation(plugin);
        verifyPluginServerDeployment(plugin);
    }

    private void verifyPluginDbDeployment(EntandoPlugin plugin) {
        Deployment deployment = helper.getClient().apps().deployments()
                .inNamespace(plugin.getMetadata().getNamespace())
                .withName(plugin.getMetadata().getName() + "-db-deployment").fromServer().get();
        assertThat(thePortNamed(DB_PORT)
                        .on(theContainerNamed("db-container").on(deployment))
                        .getContainerPort(),
                is(DBMS_STRATEGY.getPort()));
        if (EntandoOperatorSpiConfig.getComplianceMode() == EntandoOperatorComplianceMode.COMMUNITY) {
            assertThat(thePrimaryContainerOn(deployment).getImage(), containsString("centos/postgresql-12-centos7"));
        } else {
            assertThat(thePrimaryContainerOn(deployment).getImage(), containsString("rhel8/postgresql-12"));
        }
        Service dbService = helper.getClient().services().inNamespace(plugin.getMetadata().getNamespace())
                .withName(plugin.getMetadata().getName() + "-db-service").fromServer().get();
        assertThat(thePortNamed(DB_PORT).on(dbService).getPort(), is(DBMS_STRATEGY.getPort()));
        await().atMost(20, TimeUnit.SECONDS).ignoreExceptions().until(() -> deployment.getStatus().getReadyReplicas() >= 1);
    }
}
