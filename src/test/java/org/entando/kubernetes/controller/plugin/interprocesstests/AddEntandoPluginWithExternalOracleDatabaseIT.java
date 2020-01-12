package org.entando.kubernetes.controller.plugin.interprocesstests;

import org.entando.kubernetes.controller.integrationtest.support.EntandoPluginIntegrationTestHelper;
import org.entando.kubernetes.controller.integrationtest.support.SampleWriter;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("oracle-end-to-end")

public class AddEntandoPluginWithExternalOracleDatabaseIT extends AddEntandoPluginBaseIT {

    @Test
    @SuppressWarnings("PMD.JUnitTestsShouldIncludeAssert") // The assertions are enclosed in the verifyMethods
    public void testCreate() {
        //Given I have an external PostgreSQL database
        helper.externalDatabases()
                .prepareExternalOracleDatabase(EntandoPluginIntegrationTestHelper.TEST_PLUGIN_NAMESPACE, "TEST_PLUGIN_A_PLUGINDB");
        //When I create an EntandoPlugin custom resource
        EntandoPlugin plugin = new EntandoPluginBuilder().withNewSpec().withImage("entando/entando-avatar-plugin")
                .withDbms(DBMS)
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
