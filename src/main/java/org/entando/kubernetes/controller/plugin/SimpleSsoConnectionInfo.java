package org.entando.kubernetes.controller.plugin;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;

public class SimpleSsoConnectionInfo implements SsoConnectionInfo {

    private final CapabilityProvisioningResult capabilityResult;
    private final String tenantCode;

    public SimpleSsoConnectionInfo(CapabilityProvisioningResult capabilityResult, String tenantCode) {
        this.capabilityResult = capabilityResult;
        this.tenantCode = tenantCode;
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
                .addToStringData(Map.of("username", "entando_keycloak_admin", "password", "f49720353d2042c5"))
                .build();
    }

    @Override
    public String getExternalBaseUrl() {
        return "http://mt.10.214.197.61.nip.io/auth";
    }

    @Override
    public Optional<String> getDefaultRealm() {
        return Optional.of("tenant1");
    }

    @Override
    public Optional<String> getInternalBaseUrl() {
        return Optional.of("http://default-sso-in-namespace-service.mt.svc.cluster.local:8080/auth");
    }

}
