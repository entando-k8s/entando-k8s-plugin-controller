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

package org.entando.kubernetes.controller.plugin.interprocesstests;

import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("oracle")})

public class AddEntandoPluginWithExternalOracleDatabaseIT extends AddEntandoPluginBaseIT {

    @Test
    public void testCreate() {
        //Given I have an external PostgreSQL database
        helper.externalDatabases()
                .prepareExternalOracleDatabase(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE, "TEST_PLUGIN_A_PLUGINDB");
        //When I create an EntandoPlugin custom resource
        EntandoPlugin plugin = new EntandoPluginBuilder().withNewSpec().withImage("entando/entando-avatar-plugin")
                .withDbms(DbmsVendor.ORACLE)
                .withReplicas(1)
                .withIngressHostName(pluginHostName)
                .withIngressPath("/avatarPlugin")
                .withHealthCheckPath("/management/health")
                .withSecurityLevel(PluginSecurityLevel.STRICT)
                .addNewConnectionConfigName(EntandoPluginIntegrationTestHelper.PAM_CONNECTION_CONFIG)
                .endSpec().build();
        plugin.getMetadata().setName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME);
        SampleWriter.writeSample(plugin, "plugin-with-external-oracle-db");
        helper.createAndWaitForPlugin(plugin, false);
        verifyPluginServerDeployment();
    }

}
