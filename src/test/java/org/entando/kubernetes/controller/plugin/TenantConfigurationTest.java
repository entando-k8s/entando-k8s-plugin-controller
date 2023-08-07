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

import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TenantConfigurationTest {

    @Test
    void shouldConstructorFromMapAndCopyConstructorWorkFine() {

        Map<String, String> map = Map.of("tenantCode", "tenant1",
                "kcAuthUrl", "true",
                "kcInternalAuthUrl", "",
                "kcRealm", "tenant1",
                "kcAdminUsername", "", "kcAdminPassword", "");

        TenantConfiguration tc = new TenantConfiguration(map);

        TenantConfiguration clone = new TenantConfiguration(tc);
        Map<String, String> map2 = map.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        map2.put("", null);
        clone.putAll(map2);

        Map<String, String> mapResult = clone.getAll();

        Assertions.assertThat(mapResult).hasSize(map2.size()).containsOnlyKeys(map2.keySet());
        Assertions.assertThat(clone.getKcRealm()).isEqualTo("tenant1");
    }

    @Test
    void shouldThrowExceptionIfPropertyNotFound() {

        Map<String, String> map = Map.of(
                        "tenantCode", "tenant1",
                        "kcAuthUrl", "true",
                        "kcInternalAuthUrl", "",
                        "kcRealm", "tenant1",
                        "kcAdminUsername", "");

        TenantConfiguration tc = new TenantConfiguration(map);
        Assertions.assertThatThrownBy(() -> tc.getKcAdminPassword())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tenant parameter 'kcAdminPassword' not found for tenant: 'tenant1'");
    }

    @Test
    void shouldDefaultGetWorkFine() {
        Map<String, String> map = Map.of("tenantCode", "1",
                "kcAuthUrl", "2",
                "kcInternalAuthUrl", "3",
                "kcRealm", "4",
                "kcAdminUsername", "5", "kcAdminPassword", "6");

        TenantConfiguration tc = new TenantConfiguration(map);

        Assertions.assertThat(tc.getTenantCode()).isEqualTo("1");
        Assertions.assertThat(tc.getKcAuthUrl()).isEqualTo("2");
        Assertions.assertThat(tc.getKcInternalAuthUrl()).isEqualTo("3");
        Assertions.assertThat(tc.getKcRealm()).isEqualTo("4");
        Assertions.assertThat(tc.getKcAdminUsername()).isEqualTo("5");
        Assertions.assertThat(tc.getKcAdminPassword()).isEqualTo("6");

    }
    
}
