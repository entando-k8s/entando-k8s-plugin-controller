/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.plugin;

import static io.qameta.allure.Allure.step;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_POD_COMPLETION_TIMEOUT_SECONDS;
import static org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty.ENTANDO_POD_READINESS_TIMEOUT_SECONDS;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.PrettyLoggable;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.test.common.SourceLink;
import org.entando.kubernetes.test.common.VariableReferenceAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("component"), @Tag("in-process"), @Tag("allure")})
@Feature("As a deployer, when I deploy an EntandoPlugin hitting a timeout, I would like that everything rolls back")
@Issue("ENG-3272")
@SourceLink("DeployEntandoPluginTimeoutTest.java")
@SuppressWarnings({
        "java:S5961"})//because this test is intended to generate documentation and should read like the generated document
class DeployEntandoPluginTimeoutTest extends PluginTestBase implements VariableReferenceAssertions {

    private EntandoPlugin plugin;

    @Test
    @Description("Should rollback a plugin after timeout")
    void shouldRollbackAPluginAfterHavingReachedTheTimeout() {

        this.plugin = new EntandoPluginBuilder()
                .withNewMetadata()
                .withName(MY_PLUGIN)
                .withNamespace(MY_NAMESPACE)
                .endMetadata()
                .withNewSpec()
                .withDbms(DbmsVendor.POSTGRESQL)
                .withImage("entando/my-plugin:6.2.1")
                .endSpec()
                .build();
        step("Given that the Operator runs in a Kubernetes environment the requires a filesystem user/group override for mounted volumes",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_REQUIRES_FILESYSTEM_GROUP_OVERRIDE, "true"));
        step("And the routing suffix has been configured globally ",
                () -> attachEnvironmentVariable(EntandoOperatorConfigProperty.ENTANDO_DEFAULT_ROUTING_SUFFIX, THE_ROUTING_SUFFIX));
        theDefaultTlsSecretWasCreatedAndConfiguredAsDefault();
        step("And there is a controller to process requests for the DBMS capability",
                () -> doAnswer(withADatabaseCapabilityStatus(DbmsVendor.POSTGRESQL, "my_db")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.DBMS)), anyInt()));
        step("And there is a controller to process requests for the SSO capability",
                () -> doAnswer(withAnSsoCapabilityStatus("mykeycloak.apps.serv.run", "my-realm")).when(client.capabilities())
                        .waitForCapabilityCompletion(argThat(matchesCapability(StandardCapability.SSO)), anyInt()));
        step("And I set to 1 both values for pod completion and pod readiness",
                () -> {
                    System.setProperty(ENTANDO_POD_COMPLETION_TIMEOUT_SECONDS.name(), "1");
                    System.setProperty(ENTANDO_POD_READINESS_TIMEOUT_SECONDS.name(), "1");
                });
        step("When I create an EntandoPlugin with minimal configuration",
                () -> {
                    if (this.plugin.getMetadata().getResourceVersion() != null) {
                        this.plugin = getClient().entandoResources().reload(plugin);
                    }
                    doAnswer((invocationOnMock) -> {
                        Pod pod = (Pod) invocationOnMock.callRealMethod();
                        try {
                            await().atMost(10, TimeUnit.SECONDS).until(() -> false);
                        } catch (ConditionTimeoutException ex) {
                            // nothing
                        }
                        return pod;
                    }).when(getClient().pods()).waitForPod(anyString(), anyString(), anyString(), anyInt());

                    KubernetesClientForControllers k8sClientCont = this.getClient().entandoResources();

                    var mockedPod = mock(PodResource.class);
                    var mockedLoggable = mock(PrettyLoggable.class);
                    var mockedDeployment = mock(RollableScalableResource.class);

                    when(k8sClientCont.getPodByName(anyString(), anyString())).thenReturn(mockedPod);
                    when(k8sClientCont.getDeploymentByName(anyString(), anyString())).thenReturn(mockedDeployment);

                    var res = new ArrayList<String>();

                    doAnswer((invocationOnMock) -> mockedLoggable).when(mockedPod).tailingLines(anyInt());

                    doAnswer((invocationOnMock) -> {
                        res.add("<<example log>>");
                        return "<<example log>>";
                    }).when(mockedLoggable).getLog();

                    doAnswer((invocationOnMock) -> {
                        Pod pod = (Pod) invocationOnMock.callRealMethod();
                        try {
                            await().atMost(10, TimeUnit.SECONDS).until(() -> false);
                        } catch (ConditionTimeoutException ex) {
                            // nothing
                        }
                        return pod;
                    }).when(getClient().pods()).waitForPod(anyString(), anyString(), anyString(), anyInt());

                    try {
                        runControllerAgainstCustomResource(plugin);
                    } catch (RuntimeException ex) {
                        // nothing
                    }

                    assertThat(res.get(0)).isEqualTo("<<example log>>");
                }
        );
    }
}
