package org.entando.kubernetes.controller.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.model.common.EntandoMultiTenancy;

public class TenantConfigurationService {

    private static final Logger LOGGER = Logger.getLogger(TenantConfigurationService.class.getName());

    private final KubernetesClientForControllers k8sClient;
    //private Optional<TenantConfiguration> tenantConfiguration;

    public TenantConfigurationService(KubernetesClientForControllers k8sClient) {
        this.k8sClient = k8sClient;
        //  this.tenantConfiguration = Optional.empty();
    }

    private TenantConfiguration fetchTenantConfiguration(String tenantCode) {
        String tenantConfigsString = fetchEntandoTenantConfigs();
        List<TenantConfiguration> tenantConfigs = parseEntandoTenantConfigs(tenantConfigsString);
        return tenantConfigs.stream()
                .filter(tenantConfiguration -> StringUtils.equals(tenantCode, tenantConfiguration.getTenantCode()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        String.format("tenant configuration not found for tenantCode: '%s'", tenantCode)));
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
                throw new IllegalArgumentException(
                        String.format("Error in parse tenant configuration: '%s'", tenantsConfigAsString), e);
            }

            list.stream().filter(tc -> EntandoMultiTenancy.PRIMARY_TENANT.equalsIgnoreCase(tc.getTenantCode()))
                    .findFirst().ifPresent(tc -> {
                        LOGGER.log(Level.SEVERE,
                                String.format("You cannot use '%s' as tenant code", EntandoMultiTenancy.PRIMARY_TENANT));
                        throw new IllegalArgumentException(
                                String.format("You cannot use '%s' as tenant code", EntandoMultiTenancy.PRIMARY_TENANT));
                    });

        }
        return list;
    }

    private String fetchEntandoTenantConfigs() {
        // FIXME only for test purpose
        return "[\n"
                + "  {\n"
                + "    \"tenantCode\": \"tenant1\",\n"
                + "    \"kcAuthUrl\": \"ent.10.4.100.225.nip.io/auth\",\n"
                + "    \"kcInternalAuthUrl\": \"http://default-sso-in-namespace-service.ent.svc.cluster.local:8080/auth\",\n"
                + "    \"kcRealm\": \"entando\",\n"
                + "    \"kcAdminUsername\": \"entando_keycloak_admin\",\n"
                + "    \"kcAdminPassword\": \"41814475bec245bb\",\n"
                + "  },\n"
                + "  {\n"
                + "    \"tenantCode\": \"tenant2\",\n"
                + "    \"kcAuthUrl\": \"tenant2.10.4.100.225.nip.io/auth\",\n"
                + "    \"kcInternalAuthUrl\": \"http://default-sso-in-namespace-service.ent.svc.cluster.local:8080/auth\",\n"
                + "    \"kcRealm\": \"realm2\",\n"
                + "    \"kcAdminUsername\": \"entando_keycloak_admin\",\n"
                + "    \"kcAdminPassword\": \"41814475bec245bb\",\n"
                + "  }\n"
                + "]";
    }

    public String getRealm(String tenantCode) {
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
