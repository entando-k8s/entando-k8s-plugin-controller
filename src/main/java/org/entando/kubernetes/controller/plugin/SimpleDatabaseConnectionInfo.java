package org.entando.kubernetes.controller.plugin;

import java.util.Map;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.common.DbmsVendorConfig;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.model.common.DbmsVendor;

public class SimpleDatabaseConnectionInfo implements DatabaseConnectionInfo {

    private final KubernetesClientForControllers k8sClient;
    private final String tenantCode;

    public SimpleDatabaseConnectionInfo(KubernetesClientForControllers k8sClient, String tenantCode) {
        this.k8sClient = k8sClient;
        this.tenantCode = tenantCode;
    }

    /*
    {
        "dbMaxTotal": "5",
            "tenantCode": "tenant1",
            "initializationAtStartRequired": "false",
            "fqdns": "tenant1.mt720.k8s-entando.org",
            "kcEnabled": true,
            "kcAuthUrl": "https://mt720.k8s-entando.org/auth",
            "kcRealm": "tenant1",
            "kcClientId": "quickstart",
            "kcClientSecret": "UbcFsEJavAHJfiCaYIIPfuVFjbgir0tI",
            "kcPublicClientId": "entando-web",
            "kcSecureUris": "",
            "kcDefaultAuthorizations": "",
            "dbDriverClassName": "org.postgresql.Driver",
            "dbUrl": "jdbc:postgresql://default-postgresql-dbms-in-namespace-service.test-mt-720.svc.cluster.local:5432/tenant1",
            "dbUsername": "postgres",
            "dbPassword": "ed81b977b8ac447c",
            "cdsPublicUrl": "https://cds-mt720.k8s-entando.org/tenant1/",
            "cdsPrivateUrl": "http://mt720-cds-tenant1-service.test-mt-720.svc.cluster.local:8080/",
            "cdsPath": "api/v1",
            "solrAddress": "http://solr-solrcloud-common.test-mt-720.svc.cluster.local/solr",
            "solrCore": "tenant1"
    },
    */
    @Override
    public Map<String, String> getJdbcParameters() {
        return null;
    }

    @Override
    public String getDatabaseName() {
        return null;
    }

    @Override
    public DbmsVendorConfig getVendor() {
        return DbmsVendorConfig.valueOf(getValueByKey("dbVendor").orElse(DbmsVendor.POSTGRESQL.name()));
    }

    @Override
    public Optional<String> getTablespace() {
        return Optional.empty();
    }

    @Override
    public String getInternalServiceHostname() {
        return null;
    }

    @Override
    public String getPort() {
        return null;
    }

    @Override
    public String getAdminSecretName() {
        return null;
    }

    private Optional<String> getValueByKey(String key) {
        return Optional.empty();
    }

}
