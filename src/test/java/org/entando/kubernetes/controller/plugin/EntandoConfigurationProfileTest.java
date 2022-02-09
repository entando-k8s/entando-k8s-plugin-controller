package org.entando.kubernetes.controller.plugin;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import java.util.HashMap;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("in-process")})
class EntandoConfigurationProfileTest {

    @Test
    void testEntandoConfigurationProfile_FormatError() {
        var dcp = new EntandoConfigurationProfile();

        // NO SETTING
        prepateTestDataWith(TestData.CONFIGMAP_INLINE_PLUGIN_PROFILE);
        dcp.loadForPlugin(TestData.TEST_PLUGIN_NAME);

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(3001);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(20001);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(1501);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(1601);

        prepateTestDataWith(TestData.CONFIGMAP_INVALID_INLINE_PLUGIN_PROFILE);
        dcp.loadForPlugin(TestData.TEST_PLUGIN_NAME);

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(3000);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(20000);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(1500);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(1600);
    }

    @Test
    void testEntandoConfigurationProfile_Getters() {
        var dcp = new EntandoConfigurationProfile();

        prepateTestDataWith(TestData.CONFIGMAP_INLINE_PLUGIN_PROFILE);
        dcp.loadForPluginDeployment(TestData.TEST_PLUGIN_NAME + "-deployment");

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(3001);
        assertThat(dcp.getString("resources.limits.cpu", "-1D")).isEqualTo("3001");
        assertThat(dcp.getString("resources.limits.nonexistent", "XXX")).isEqualTo("XXX");
    }

    @Test
    void testEntandoConfigurationProfile_WithInlineProfile_ViaPluginDeploymentName() {
        var dcp = new EntandoConfigurationProfile();

        // NO SETTING
        prepateTestDataWith(TestData.CONFIGMAP_INLINE_PLUGIN_PROFILE);
        dcp.loadForPluginDeployment(TestData.TEST_PLUGIN_NAME + "-deployment");

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(3001);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(20001);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(1501);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(1601);
    }

    @Test
    void testEntandoConfigurationProfile_WithInlineProfile() {
        var dcp = new EntandoConfigurationProfile();

        // NO SETTING
        prepateTestDataWith(TestData.CONFIGMAP_INLINE_PLUGIN_PROFILE);
        dcp.loadForPlugin(TestData.TEST_PLUGIN_NAME);

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(3001);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(20001);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(1501);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(1601);
    }

    @Test
    void testEntandoConfigurationProfile_WithMappedProfile() {
        var dcp = new EntandoConfigurationProfile();

        // NO SETTING
        prepateTestDataWith(TestData.CONFIGMAP_MAPPED_PROFILE);
        dcp.loadForPlugin(TestData.TEST_PLUGIN_NAME);

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(3000);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(20000);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(1500);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(1600);
    }

    @Test
    void testEntandoConfigurationProfile_WithDefaultProfile() {
        var dcp = new EntandoConfigurationProfile();

        // NO SETTING
        prepateTestDataWith(TestData.CONFIGMAP_CUSTOM_DEFAULT_PROFILE);
        dcp.loadForPlugin(TestData.TEST_PLUGIN_NAME);

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(1200);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(2000);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(500);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(600);
    }

    @Test
    void testEntandoConfigurationProfile_WithNoSettings() {
        var dcp = new EntandoConfigurationProfile();

        // NO SETTING
        prepateTestDataWith(TestData.CONFIGMAP_BASE);
        dcp.loadForPlugin(TestData.TEST_PLUGIN_NAME);

        assertThat(dcp.getNumber("resources.limits.cpu", -1D)).isEqualTo(-1D);
        assertThat(dcp.getNumber("resources.limits.memory", -2D)).isEqualTo(-2D);
        assertThat(dcp.getNumber("resources.requests.cpu", -3D)).isEqualTo(-3D);
        assertThat(dcp.getNumber("resources.requests.memory", -4D)).isEqualTo(-4D);
    }

    private void prepateTestDataWith(HashMap<String, String> data) {
        var configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withNamespace("test-namespace")
                .withName(KubernetesClientForControllers.ENTANDO_CRD_NAMES_CONFIG_MAP)
                .endMetadata()
                .addToData(data)
                .build();

        EntandoOperatorConfigBase.setConfigMap(configMap);
    }


    static class TestData {

        static String TEST_PLUGIN_NAME = "my-test-plugin";

        static final HashMap<String, String> CONFIGMAP_BASE = new HashMap<>() {{
                put("entando.ca.secret.name", "test-keycloak-server-ca-cert");
                put("entando.timeout.adjustment.ratio", "2");
            }};

        static final HashMap<String, String> CONFIGMAP_CUSTOM_DEFAULT_PROFILE = new HashMap<>();

        static {
            CONFIGMAP_CUSTOM_DEFAULT_PROFILE.putAll(CONFIGMAP_BASE);
            CONFIGMAP_CUSTOM_DEFAULT_PROFILE.put("entando.plugins.defaultProfile", "mid");
            CONFIGMAP_CUSTOM_DEFAULT_PROFILE.put("entando.profile.mid", ""
                    + "    resources.limits.cpu: \"1200\"\n"
                    + "    resources.limits.memory: \"2000\"\n"
                    + "    resources.requests.cpu: \"500\"\n"
                    + "    resources.requests.memory: \"600\"\n");
        }

        static final HashMap<String, String> CONFIGMAP_MAPPED_PROFILE = new HashMap<>();

        static {
            CONFIGMAP_MAPPED_PROFILE.putAll(CONFIGMAP_CUSTOM_DEFAULT_PROFILE);
            CONFIGMAP_MAPPED_PROFILE.put("entando.plugins.profileMapping", ""
                    + "    " + TEST_PLUGIN_NAME + ": mid");

            CONFIGMAP_MAPPED_PROFILE.put("entando.profile.mid", ""
                    + "    resources.limits.cpu: \"3000\"\n"
                    + "    resources.limits.memory: \"20000\"\n"
                    + "    resources.requests.cpu: \"1500\"\n"
                    + "    resources.requests.memory: \"1600\"\n");

        }

        static final HashMap<String, String> CONFIGMAP_INLINE_PLUGIN_PROFILE = new HashMap<>();

        static {
            CONFIGMAP_INLINE_PLUGIN_PROFILE.putAll(CONFIGMAP_MAPPED_PROFILE);

            CONFIGMAP_INLINE_PLUGIN_PROFILE.put("entando.profile.plugins." + TEST_PLUGIN_NAME + "", ""
                    + "    resources.limits.cpu: \"3001\"\n"
                    + "    resources.limits.memory: \"20001\"\n"
                    + "    resources.requests.cpu: \"1501\"\n"
                    + "    resources.requests.memory: \"1601\"\n");
        }

        static final HashMap<String, String> CONFIGMAP_INVALID_INLINE_PLUGIN_PROFILE = new HashMap<>();

        static {
            CONFIGMAP_INVALID_INLINE_PLUGIN_PROFILE.putAll(CONFIGMAP_MAPPED_PROFILE);
            CONFIGMAP_INVALID_INLINE_PLUGIN_PROFILE.put("entando.profile.plugins." + TEST_PLUGIN_NAME + "",
                    "<<TOTALLY-NOT-A-YAML>>");
        }
    }
}