package org.entando.kubernetes.controller.plugin;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;

public class SsoConnectionInfoTenantAware implements SsoConnectionInfo {

    private final String tenantCode;
    private final String namespace;
    private final TenantConfigurationService tenantConfigurationService;

    public SsoConnectionInfoTenantAware(String tenantCode, String namespace,
            KubernetesClientForControllers k8sClient) {
        this.tenantCode = tenantCode;
        this.namespace = namespace;
        this.tenantConfigurationService = new TenantConfigurationService(k8sClient, namespace);
    }

    @Override
    public String getBaseUrlToUse() {
        return EntandoOperatorSpiConfig.forceExternalAccessToKeycloak()
                ? this.getExternalBaseUrl() : (String) this.getInternalBaseUrl().orElse(this.getExternalBaseUrl());
    }

    @Override
    public Secret getAdminSecret() {
        return new SecretBuilder()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName(makeKubernetesCompatible(tenantCode) + "-tenant-sso-secret")
                .endMetadata()
                .addToStringData(
                        Map.of("username", tenantConfigurationService.getKcAdminUsername(tenantCode), "password",
                                tenantConfigurationService.getKcAdminPassword(tenantCode)))
                .build();
    }

    public static String makeKubernetesCompatible(String value) {
        return value.toLowerCase()
                .replaceAll("[\\/\\.\\:_]", "-");
    }

    @Override
    public String getExternalBaseUrl() {
        return tenantConfigurationService.getKcAuthUrl(tenantCode);
    }

    @Override
    public Optional<String> getDefaultRealm() {
        return Optional.of(tenantConfigurationService.getKcRealm(tenantCode));
    }

    @Override
    public Optional<String> getInternalBaseUrl() {
        return Optional.of(tenantConfigurationService.getKcInternalAuthUrl(tenantCode));
    }
}