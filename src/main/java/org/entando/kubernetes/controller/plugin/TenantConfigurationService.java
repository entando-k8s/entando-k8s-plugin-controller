package org.entando.kubernetes.controller.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Secret;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.model.common.EntandoMultiTenancy;

public class TenantConfigurationService {

    private static final Logger LOGGER = Logger.getLogger(TenantConfigurationService.class.getName());
    private static final String ENTANDO_TENANT_SECRET = "entando-tenants-secret";
    private static final String ENTANDO_TENANT_SECRET_KEY = "ENTANDO_TENANTS";
    private final KubernetesClientForControllers k8sClient;
    private final String namespace;

    private Optional<TenantConfiguration> tenantConfiguration;

    public TenantConfigurationService(KubernetesClientForControllers k8sClient, String namespace) {
        this.k8sClient = k8sClient;
        this.namespace = namespace;
        this.tenantConfiguration = Optional.empty();
    }

    private TenantConfiguration fetchTenantConfiguration(String tenantCode) {
        return tenantConfiguration.orElseGet(() -> {
            String tenantConfigsString = fetchEntandoTenantConfigs();
            List<TenantConfiguration> tenantConfigs = parseEntandoTenantConfigs(tenantConfigsString);
            TenantConfiguration tc = tenantConfigs.stream()
                    .filter(tenantConf -> StringUtils.equals(tenantCode, tenantConf.getTenantCode()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            String.format("tenant configuration not found for tenantCode: '%s'", tenantCode)));
            tenantConfiguration = Optional.ofNullable(tc);
            return tc;
        });
    }

    private List<TenantConfiguration> parseEntandoTenantConfigs(String tenantsConfigAsString) {
        List<TenantConfiguration> list = Collections.emptyList();

        if (StringUtils.isNotBlank(tenantsConfigAsString)) {
            try {
                list = new ObjectMapper()
                        .readValue(tenantsConfigAsString, new TypeReference<List<Map<String, String>>>() {})
                        .stream()
                        .map(TenantConfiguration::new)
                        .collect(Collectors.toList());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        String.format("Error in parse tenant configuration: '%s'", tenantsConfigAsString), e);
            }

            list.stream().filter(tc -> EntandoMultiTenancy.PRIMARY_TENANT.equalsIgnoreCase(tc.getTenantCode()))
                    .findFirst().ifPresent(tc -> {
                        LOGGER.log(Level.SEVERE,
                                String.format("You cannot use '%s' as tenant code", EntandoMultiTenancy.PRIMARY_TENANT));
                        throw new IllegalStateException(
                                String.format("You cannot use '%s' as tenant code", EntandoMultiTenancy.PRIMARY_TENANT));
                    });

        }
        return list;
    }

    private String fetchEntandoTenantConfigs() {
        return Optional.ofNullable(k8sClient.getSecretByName(ENTANDO_TENANT_SECRET, namespace).get()).map(this::unpackTenantSecret)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("Unable to load secret with name '%s'", ENTANDO_TENANT_SECRET)));
    }

    private String unpackTenantSecret(Secret secret) {
        Optional<String> value = Optional.ofNullable(secret.getData())
                .map(data -> Optional.ofNullable(data.get(ENTANDO_TENANT_SECRET_KEY))
                        .map(s -> new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)))
                .orElseGet(() -> Optional.ofNullable(secret.getStringData())
                        .map(data -> data.get(ENTANDO_TENANT_SECRET_KEY)));

        return value.orElseThrow(
                () -> new IllegalStateException(String.format("Unable to load from secret value with key '%s'",
                        ENTANDO_TENANT_SECRET_KEY)));
    }

    public String getKcRealm(String tenantCode) {
        return fetchTenantConfiguration(tenantCode).getKcRealm();
    }

    public String getKcAuthUrl(String tenantCode) {
        return fetchTenantConfiguration(tenantCode).getKcAuthUrl();
    }

    public String getKcInternalAuthUrl(String tenantCode) {
        return fetchTenantConfiguration(tenantCode).getKcInternalAuthUrl();
    }

    public String getKcAdminUsername(String tenantCode) {
        return fetchTenantConfiguration(tenantCode).getKcAdminUsername();
    }

    public String getKcAdminPassword(String tenantCode) {
        return fetchTenantConfiguration(tenantCode).getKcAdminPassword();
    }
}
