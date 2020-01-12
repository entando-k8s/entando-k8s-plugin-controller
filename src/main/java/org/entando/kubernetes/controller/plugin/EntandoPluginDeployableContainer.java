package org.entando.kubernetes.controller.plugin;

import io.fabric8.kubernetes.api.model.EnvVar;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KeycloakConnectionConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.creators.DeploymentCreator;
import org.entando.kubernetes.controller.database.DatabaseSchemaCreationResult;
import org.entando.kubernetes.controller.spi.DatabasePopulator;
import org.entando.kubernetes.controller.spi.DbAware;
import org.entando.kubernetes.controller.spi.IngressingContainer;
import org.entando.kubernetes.controller.spi.KeycloakAware;
import org.entando.kubernetes.controller.spi.PersistentVolumeAware;
import org.entando.kubernetes.controller.spi.TlsAware;
import org.entando.kubernetes.model.DbmsImageVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;

public class EntandoPluginDeployableContainer implements IngressingContainer, KeycloakAware, PersistentVolumeAware, DbAware, TlsAware {

    private final EntandoPlugin entandoPlugin;
    private final KeycloakConnectionConfig keycloakConnectionConfig;
    private Map<String, DatabaseSchemaCreationResult> dbSchemas;

    public EntandoPluginDeployableContainer(EntandoPlugin entandoPlugin, KeycloakConnectionConfig keycloakConnectionConfig) {
        this.entandoPlugin = entandoPlugin;
        this.keycloakConnectionConfig = keycloakConnectionConfig;
    }

    @Override
    public int getCpuLimitMillicores() {
        return 1000;
    }

    @Override
    public int getMemoryLimitMebibytes() {
        return 1024;
    }

    @Override
    public List<String> getNamesOfSecretsToMount() {
        return entandoPlugin.getSpec().getConnectionConfigNames();
    }

    @Override
    public String determineImageToUse() {
        return entandoPlugin.getSpec().getImage();
    }

    @Override
    public String getNameQualifier() {
        return KubeUtils.DEFAULT_SERVER_QUALIFIER;
    }

    @Override
    public int getPort() {
        return 8081;
    }

    @Override
    public String getVolumeMountPath() {
        return "/entando-data";
    }

    @Override
    public void addEnvironmentVariables(List<EnvVar> vars) {
        vars.add(new EnvVar("PORT", "8081", null));
        DatabaseSchemaCreationResult databaseSchemaCreationResult = dbSchemas.get("plugindb");
        if (databaseSchemaCreationResult != null) {
            vars.add(new EnvVar("DB_VENDOR", databaseSchemaCreationResult.getVendor().getName(),
                    null));
            vars.add(new EnvVar("SPRING_DATASOURCE_USERNAME", null,
                    databaseSchemaCreationResult.getUsernameRef()));
            vars.add(new EnvVar("SPRING_DATASOURCE_PASSWORD", null,
                    databaseSchemaCreationResult.getPasswordRef()));
            vars.add(new EnvVar("SPRING_DATASOURCE_URL", databaseSchemaCreationResult.getJdbcUrl(),
                    null));
            vars.add(new EnvVar("SPRING_JPA_DATABASE_PLATFORM",
                    databaseSchemaCreationResult.getVendor().getHibernateDialect(), null));
            /*
            Set SPRING_JPA_PROPERTIES_HIBERNATE_ID_NEW_GENERATOR_MAPPINGS to 'false' if we ever run into issues with ID Generation
            */
            databaseSchemaCreationResult.addAdditionalConfigFromDatabaseSecret(vars);
        }

        EnvVar keycloakClientSecret = vars.stream().filter(
                var -> var.getName().equals("KEYCLOAK_CLIENT_SECRET")).findAny().orElseThrow(IllegalStateException::new);
        EnvVar keycloakClientId = vars.stream().filter(
                var -> var.getName().equals("KEYCLOAK_CLIENT_ID")).findAny().orElseThrow(IllegalStateException::new);
        EnvVar keycloakAuthUrl = vars.stream().filter(
                var -> var.getName().equals("KEYCLOAK_AUTH_URL")).findAny().orElseThrow(IllegalStateException::new);
        EnvVar keycloakRealm = vars.stream().filter(
                var -> var.getName().equals("KEYCLOAK_REALM")).findAny().orElseThrow(IllegalStateException::new);

        String keycloakIssuerUri = keycloakAuthUrl.getValue() + "/realms/" + keycloakRealm.getValue();

        vars.add(new EnvVar("SPRING_PROFILES_ACTIVE", "default,prod", null));
        vars.add(new EnvVar("SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_CLIENT_ID", null,
                keycloakClientId.getValueFrom()));
        vars.add(new EnvVar("SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_CLIENT_SECRET", null,
                keycloakClientSecret.getValueFrom()));
        vars.add(new EnvVar("SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI", keycloakIssuerUri, null));
        vars.add(new EnvVar("ENTANDO_CONFIG_SERVICE_URI", "http://localhost:8083/config-service", null));
        vars.add(new EnvVar("ENTANDO_AUTH_SERVICE_URI", "todo", null));
        vars.add(new EnvVar("ENTANDO_WIDGETS_FOLDER", "/app/resources/widgets", null));
        vars.add(new EnvVar("ENTANDO_CONNECTIONS_ROOT", DeploymentCreator.ENTANDO_SECRET_MOUNTS_ROOT, null));
        vars.add(new EnvVar("ENTANDO_PLUGIN_SECURITY_LEVEL",
                entandoPlugin.getSpec().getSecurityLevel().orElse(PluginSecurityLevel.STRICT).name(), null));
        vars.add(new EnvVar("PLUGIN_SIDECAR_PORT", "8084", null));
    }

    public KeycloakConnectionConfig getKeycloakConnectionConfig() {
        return keycloakConnectionConfig;
    }

    @Override
    public KeycloakClientConfig getKeycloakClientConfig() {
        return new KeycloakClientConfig(KubeUtils.ENTANDO_KEYCLOAK_REALM,
                entandoPlugin.getMetadata().getName() + "-" + getNameQualifier(),
                entandoPlugin.getMetadata().getName(), entandoPlugin.getSpec().getRoles(),
                entandoPlugin.getSpec().getPermissions())
                .withRole(KubeUtils.ENTANDO_APP_ROLE)
                .withPermission(EntandoPluginSidecarDeployableContainer.keycloakClientIdOf(entandoPlugin),
                        EntandoPluginSidecarDeployableContainer.REQUIRED_ROLE);
    }

    @Override
    public Optional<String> getHealthCheckPath() {
        return Optional.of(getWebContextPath() + entandoPlugin.getSpec().getHealthCheckPath());
    }

    @Override
    public String getWebContextPath() {
        return entandoPlugin.getSpec().getIngressPath();
    }

    @Override
    public List<String> getDbSchemaQualifiers() {
        if (entandoPlugin.getSpec().getDbms().orElse(DbmsImageVendor.NONE) == DbmsImageVendor.NONE) {
            return Collections.emptyList();
        } else {
            return Arrays.asList("plugindb");
        }
    }

    @Override
    public Optional<DatabasePopulator> useDatabaseSchemas(Map<String, DatabaseSchemaCreationResult> dbSchemas) {
        this.dbSchemas = dbSchemas;
        return Optional.empty();
    }
}
