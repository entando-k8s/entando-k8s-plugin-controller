/*
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package org.entando.kubernetes.controller.plugin;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class TenantConfigurationServiceTest {

    @Mock
    private KubernetesClientForControllers k8sClient;

    @Test
    void shouldManageMissedOrWrongConfiguration() {
        final String ns = "nstest";
        Resource<Secret> secretResource = mock(Resource.class);
        when(secretResource.get()).thenReturn(null);
        when(k8sClient.getSecretByName("entando-tenants-secret", ns)).thenReturn(secretResource);

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, "nstest");
        Exception ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load secret with name 'entando-tenants-secret'", ex.getMessage());

        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .build();
        when(secretResource.get()).thenReturn(sec);
        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load from secret value with key 'ENTANDO_TENANTS'", ex.getMessage());


        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToStringData("key", "value")
                .build();
        when(secretResource.get()).thenReturn(sec);

        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load from secret value with key 'ENTANDO_TENANTS'", ex.getMessage());

        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToData("key", "value")
                .build();
        when(secretResource.get()).thenReturn(sec);

        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Unable to load from secret value with key 'ENTANDO_TENANTS'", ex.getMessage());

    }

    @Test
    void shouldManageInvalidTenatConfigJson() {
        final String ns = "nstest";
        Resource<Secret> secretResource = mock(Resource.class);
        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "ppp")
                .build();
        when(secretResource.get()).thenReturn(sec);
        when(k8sClient.getSecretByName("entando-tenants-secret", ns)).thenReturn(secretResource);

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, ns);
        Exception ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("Error in parse tenant configuration: 'ppp'", ex.getMessage());

        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{\"tenantCode\":\"primary\"}]")
                .build();
        when(secretResource.get()).thenReturn(sec);
        ex = Assertions.assertThrows(IllegalStateException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("You cannot use 'primary' as tenant code", ex.getMessage());

    }

    @Test
    void shouldManageTenatConfigEmptyOrTenantCodeNotFound() {
        final String ns = "nstest";
        Resource<Secret> secretResource = mock(Resource.class);
        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "")
                .build();
        when(secretResource.get()).thenReturn(sec);
        when(k8sClient.getSecretByName("entando-tenants-secret", ns)).thenReturn(secretResource);

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, ns);
        Exception ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> tcs.getKcAdminPassword("tenant1"));
        Assertions.assertEquals("tenant configuration not found for tenantCode: 'tenant1'", ex.getMessage());

        sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{\"tenantCode\":\"tenant1\"}]")
                .build();
        when(secretResource.get()).thenReturn(sec);
        ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> tcs.getKcAdminPassword("tenant2"));
        Assertions.assertEquals("tenant configuration not found for tenantCode: 'tenant2'", ex.getMessage());

    }

    @Test
    void shouldAllWorkFine() {
        final String ns = "nstest";
        Resource<Secret> secretResource = mock(Resource.class);
        Secret sec = new SecretBuilder()
                .withNewMetadata()
                .withNamespace(ns)
                .withName("tenant1-sso-secret")
                .endMetadata()
                .addToStringData("ENTANDO_TENANTS", "[{"
                        + "\"tenantCode\":\"tenant1\","
                        + "\"kcRealm\":\"realm1\","
                        + "\"kcAuthUrl\":\"http://kchost.com/auth\","
                        + "\"kcAdminPassword\":\"psswd\""
                        + "}]")
                .build();
        when(secretResource.get()).thenReturn(sec);
        when(k8sClient.getSecretByName("entando-tenants-secret", ns)).thenReturn(secretResource);

        TenantConfigurationService tcs = new TenantConfigurationService(k8sClient, ns);
        Assertions.assertEquals("realm1", tcs.getKcRealm("tenant1"));
        Assertions.assertEquals("http://kchost.com/auth", tcs.getKcAuthUrl("tenant1"));
        Assertions.assertEquals("psswd", tcs.getKcAdminPassword("tenant1"));
        Exception ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> tcs.getKcAdminUsername("tenant1"));
        Assertions.assertEquals("tenant parameter 'kcAdminUsername' not found for tenant: 'tenant1'", ex.getMessage());
    }
}
