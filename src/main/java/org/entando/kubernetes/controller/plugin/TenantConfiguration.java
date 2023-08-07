package org.entando.kubernetes.controller.plugin;

import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

public class TenantConfiguration {

    private static final String TENANT_CODE_PROPERTY = "tenantCode";
    private static final String KC_AUTH_URL_PROPERTY = "kcAuthUrl";
    private static final String KC_INTERNAL_AUTH_URL_PROPERTY = "kcInternalAuthUrl";
    private static final String KC_REALM_PROPERTY = "kcRealm";
    private static final String KC_ADMIN_USERNAME_PROPERTY = "kcAdminUsername";
    private static final String KC_ADMIN_PASSWORD_PROPERTY = "kcAdminPassword";


    private Map<String, String> configs;

    public TenantConfiguration(Map<String, String> c) {
        configs = c;
    }

    public TenantConfiguration(TenantConfiguration t) {
        configs = t.getAll();
    }

    protected Map<String, String> getAll() {
        return configs;
    }

    protected void putAll(Map<String, String> map) {
        configs = map;
    }

    public String getTenantCode() {
        return getPropertyOrThrow(TENANT_CODE_PROPERTY);
    }

    public String getKcAuthUrl() {
        return getPropertyOrThrow(KC_AUTH_URL_PROPERTY);
    }

    public String getKcInternalAuthUrl() {
        return getPropertyOrThrow(KC_INTERNAL_AUTH_URL_PROPERTY);
    }

    public String getKcRealm() {
        return getPropertyOrThrow(KC_REALM_PROPERTY);
    }


    public String getKcAdminUsername() {
        return getPropertyOrThrow(KC_ADMIN_USERNAME_PROPERTY);
    }

    public String getKcAdminPassword() {
        return getPropertyOrThrow(KC_ADMIN_PASSWORD_PROPERTY);
    }


    private Optional<String> getProperty(String name) {
        return Optional.ofNullable(configs.get(name));
    }

    private String getPropertyOrThrow(String paramName) {
        return getProperty(paramName)
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException(
                        String.format("tenant parameter '%s' not found for tenant: '%s'", paramName, getTenantCode())));
    }
}
