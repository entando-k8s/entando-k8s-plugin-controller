package org.entando.kubernetes.controller.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.deployable.SsoClientConfig;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process")})
class EntandoPluginDeployableContainerTest {

    @Test
    void test_NameTooLongByOne() {
        String name31 = "my-plugin-xxxxxxxxxxxxxxxxxxx-XY";
        var plugin = new EntandoPluginBuilder().withNewMetadata().withName(name31).endMetadata().build();
        doReturn(DbmsVendorConfig.MYSQL).when(TD.dbi).getVendor();
        var pdc = new EntandoPluginDeployableContainer(
                plugin, "a-test-secret", TD.cap,
                TD.dbi, TD.clc, null
        );
        assertThat(pdc.getDatabaseSchema().orElseThrow().getSchemaName()).startsWith("my_plugin_xxxxxxxxxxxxxxxx_");
    }

    @Test
    void test_NameAtTheLimit() {
        String name31 = "my-plugin-xxxxxxxxxxxxxxxxxx-XY";
        var plugin = new EntandoPluginBuilder().withNewMetadata().withName(name31).endMetadata().build();
        doReturn(DbmsVendorConfig.MYSQL).when(TD.dbi).getVendor();
        var pdc = new EntandoPluginDeployableContainer(
                plugin, "a-test-secret", TD.cap,
                TD.dbi, TD.clc, null
        );
        assertThat(pdc.getDatabaseSchema().orElseThrow().getSchemaName()).isEqualTo("my_plugin_xxxxxxxxxxxxxxxxxx_XY");
    }

    @Test
    void test_NameTooLong() {
        var plugin = new EntandoPluginBuilder().withNewMetadata().withName(TD.LONG_NAME).endMetadata().build();
        doReturn(DbmsVendorConfig.MYSQL).when(TD.dbi).getVendor();
        var pdc = new EntandoPluginDeployableContainer(
                plugin, "a-test-secret", TD.cap,
                TD.dbi, TD.clc, null
        );
        assertThat(pdc.getDatabaseSchema().orElseThrow().getSchemaName()).startsWith("my_plugin_looooooooooooooo_");
    }

    @Test
    void test_NameTooLong_and_SchemaOverride() {
        var plugin = new EntandoPluginBuilder().withNewMetadata().withName(TD.LONG_NAME).endMetadata().build();
        doReturn(DbmsVendorConfig.MYSQL).when(TD.dbi).getVendor();
        var pdc = new EntandoPluginDeployableContainer(
                plugin, "a-test-secret", TD.cap,
                TD.dbi, TD.clc, "test-plugin"
        );
        assertThat(pdc.getDatabaseSchema().orElseThrow().getSchemaName()).isEqualTo("test-plugin");
    }

    @Test
    void test_NameTooLong_Postgres() {
        var plugin = new EntandoPluginBuilder().withNewMetadata().withName(TD.LONG_NAME).endMetadata().build();
        doReturn(DbmsVendorConfig.POSTGRESQL).when(TD.dbi).getVendor();
        var pdc = new EntandoPluginDeployableContainer(
                plugin, "a-test-secret", TD.cap,
                TD.dbi, TD.clc, null
        );
        assertThat(pdc.getDatabaseSchema().orElseThrow().getSchemaName()).startsWith(
                "my_plugin_looooooooooooooooooooooooooooooooooooooooooooooooo_"
        );
    }

    static class TD {

        static final String O50 = "oooooooooooooooooooooooooooooooooooooooooooooooooo";
        static final String LONG_NAME = "my-plugin-l" + O50 + O50 + O50 + O50 + O50 + "g-name";
        static final ProvidedSsoCapability cap = mock(ProvidedSsoCapability.class);
        static final DatabaseConnectionInfo dbi = mock(DatabaseConnectionInfo.class);
        static final SsoClientConfig clc = mock(SsoClientConfig.class);
    }
}
