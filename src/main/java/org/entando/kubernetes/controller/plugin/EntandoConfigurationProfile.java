package org.entando.kubernetes.controller.plugin;

import static io.fabric8.kubernetes.client.utils.Serialization.yamlMapper;
import static java.lang.String.format;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase.lookupProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helps to deal with sets of named configurations called configuration profiles and stored in the operator config-map.
 */
public class EntandoConfigurationProfile {

    private static final Logger LOGGER = Logger.getLogger(EntandoConfigurationProfile.class.getName());

    private HashMap<String, String> config = new HashMap<>();

    public String getString(String key, String fallback) {
        return config.getOrDefault(key, fallback);
    }

    public double getNumber(String key, double fallback) {
        return Double.parseDouble(config.getOrDefault(key, String.format("%f", fallback)));
    }

    /**
     * Load a configuration profile for a specific plugin deployment name.
     *
     * @see #loadForPlugin for details
     */
    public void loadForPluginDeployment(String forPluginDeploymentName) {
        if (forPluginDeploymentName.endsWith("-deployment")) {
            forPluginDeploymentName = forPluginDeploymentName.substring(0, forPluginDeploymentName.length() - 11);
        }
        loadForPlugin(forPluginDeploymentName);
    }

    /**
     * Load a configuration profile according to.
     * <pre>
     * - the plugin inline profile in the operator configmap
     * - or the profile mapped to the given plugin name in the operator configmap
     * - or the default profile in the operator configmap
     * </pre>
     */
    public void loadForPlugin(String forPluginName) {
        // Inline config profile
        config = loadMapProp("entando.profile.plugins." + forPluginName, null);
        if (config != null) {
            return;
        }

        // Mapped config profile
        var pluginsMapping = loadMapProp("entando.plugins.profileMapping", new HashMap<>());
        var profileName = pluginsMapping.getOrDefault(forPluginName, null);
        if (profileName == null) {
            profileName = lookupProperty("entando.plugins.defaultProfile").orElse("default");
        }
        config = loadMapProp("entando.profile." + profileName, new HashMap<>());
    }

    private HashMap<String, String> loadMapProp(String prop, HashMap<String, String> defaultValue) {
        final TypeReference<HashMap<String, String>> typeRef = new TypeReference<>() { };
        return lookupProperty(prop).map(cfg -> {
            try {
                return yamlMapper().readValue(cfg, typeRef);
            } catch (JsonProcessingException e) {
                LOGGER.log(Level.WARNING, e, () -> format("Error parsing the yaml embedded under key: \"%s\"", prop));
                return null;
            }
        }).orElse(defaultValue);
    }
}
