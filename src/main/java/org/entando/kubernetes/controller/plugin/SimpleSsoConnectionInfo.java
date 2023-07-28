package org.entando.kubernetes.controller.plugin;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;

public class SimpleSsoConnectionInfo implements SsoConnectionInfo {

    private final CapabilityProvisioningResult capabilityResult;
    private final String tenantCode;
    private final TenantConfigurationService tenantConfigurationService;

    public SimpleSsoConnectionInfo(CapabilityProvisioningResult capabilityResult, String tenantCode,
            KubernetesClientForControllers k8sClient) {
        this.capabilityResult = capabilityResult;
        this.tenantCode = tenantCode;
        this.tenantConfigurationService = new TenantConfigurationService(k8sClient);
    }

    @Override
    public String getBaseUrlToUse() {
        return EntandoOperatorSpiConfig.forceExternalAccessToKeycloak()
                ? this.getExternalBaseUrl() : (String) this.getInternalBaseUrl().orElse(this.getExternalBaseUrl());
    }

    //FIXME
    // manage one kc multiple admin secret for each tenant (secret name + secret)
    // manage multiple kc each admin secret for each tenant (secret name + secret)
    @Override
    public Secret getAdminSecret() {
        return new SecretBuilder()
                .withNewMetadata()
                .withNamespace("test-poc")
                .withName("test-poc-sso-secret")
                .endMetadata()
                .addToStringData(
                        Map.of("username", tenantConfigurationService.getKcAdminUsername(tenantCode), "password",
                                tenantConfigurationService.getKcAdminPassword(tenantCode)))
                .build();
    }

    @Override
    public String getExternalBaseUrl() {
        return tenantConfigurationService.getKcAuthUrl(tenantCode);
    }

    @Override
    public Optional<String> getDefaultRealm() {
        return Optional.of(tenantConfigurationService.getRealm(tenantCode));
    }

    @Override
    public Optional<String> getInternalBaseUrl() {
        return Optional.of(tenantConfigurationService.getKcInternalAuthUrl(tenantCode));
    }

}