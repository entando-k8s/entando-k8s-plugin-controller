package org.entando.kubernetes.controller.plugin.interprocesstests;

import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("end-to-end"), @Tag("inter-process"), @Tag("smoke-test")})
public class AddEntandoPluginWithExternalPostgresqlDatabaseIT extends AddEntandoPluginBaseIT {

    @Test
    public void testCreate() {
        //Given I have an external PostgreSQL database
        helper.externalDatabases()
                .prepareExternalPostgresqlDatabase(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE, "EntandoPlugin");
        //When I create an EntandoPlugin custom resource
        EntandoPlugin plugin = new EntandoPluginBuilder().withNewSpec()
                .withImage("entando/entando-avatar-plugin")
                .withDbms(DBMS)
                .withReplicas(1)
                .withHealthCheckPath("/management/health")
                .withIngressPath("/avatarPlugin")
                .withSecurityLevel(PluginSecurityLevel.LENIENT)
                .withIngressHostName(pluginHostName)
                .addNewConnectionConfigName(EntandoPluginIntegrationTestHelper.PAM_CONNECTION_CONFIG)
                .endSpec()
                .build();

        plugin.getMetadata().setName(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAME);
        SampleWriter.writeSample(plugin, "plugin-with-external-postgresql-db");
        createAndWaitForPlugin(plugin, false);
        verifyPluginServerDeployment();
    }

}

