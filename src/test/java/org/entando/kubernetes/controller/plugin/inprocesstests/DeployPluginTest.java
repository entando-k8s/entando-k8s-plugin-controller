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

package org.entando.kubernetes.controller.plugin.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.quarkus.runtime.StartupEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.entando.kubernetes.controller.EntandoOperatorConfigProperty;
import org.entando.kubernetes.controller.KeycloakClientConfig;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.SimpleKeycloakClient;
import org.entando.kubernetes.controller.creators.DeploymentCreator;
import org.entando.kubernetes.controller.creators.KeycloakClientCreator;
import org.entando.kubernetes.controller.inprocesstest.InProcessTestUtil;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.KeycloakClientConfigArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.LabeledArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.argumentcaptors.NamedArgumentCaptor;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.SimpleK8SClientDouble;
import org.entando.kubernetes.controller.k8sclient.SimpleK8SClient;
import org.entando.kubernetes.controller.plugin.EntandoPluginController;
import org.entando.kubernetes.controller.test.support.FluentTraversals;
import org.entando.kubernetes.controller.test.support.VariableReferenceAssertions;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import org.entando.kubernetes.model.plugin.EntandoPluginBuilder;
import org.entando.kubernetes.model.plugin.PluginSecurityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
//in execute component test
@Tag("in-process")

public class DeployPluginTest implements InProcessTestUtil, FluentTraversals, VariableReferenceAssertions {

    private static final String TCP = "TCP";
    private static final String MY_PLUGIN_DB = MY_PLUGIN + "-db";
    private static final String MY_PLUGIN_DB_DEPLOYMENT = MY_PLUGIN_DB + "-deployment";
    private static final String MY_PLUGIN_DB_PVC = MY_PLUGIN_DB + "-pvc";
    private static final String MY_PLUGIN_DB_SERVICE = MY_PLUGIN_DB + "-service";
    private static final String MY_PLUGIN_SERVER = MY_PLUGIN + "-server";
    private static final String MY_PLUGIN_SERVER_DEPLOYMENT = MY_PLUGIN_SERVER + "-deployment";
    private static final String MY_PLUGIN_SERVER_SERVICE = MY_PLUGIN_SERVER + "-service";
    private static final String MY_PLUGIN_SERVER_PVC = MY_PLUGIN_SERVER + "-pvc";
    private static final String DB_PORT = "db-port";
    private static final String SERVER_PORT = "server-port";
    private static final String MY_PLUGIN_DB_ADMIN_SECRET = MY_PLUGIN_DB + "-admin-secret";
    private static final String MY_PLUGIN_PLUGINDB_SECRET = MY_PLUGIN + "-plugindb-secret";
    private static final String SPRING_DATASOURCE_USERNAME = "SPRING_DATASOURCE_USERNAME";
    private static final String SPRING_DATASOURCE_PASSWORD = "SPRING_DATASOURCE_PASSWORD";
    private static final String MY_PLUGIN_SERVER_SECRET = MY_PLUGIN + "-server-secret";
    private static final int PORT_3396 = 3306;
    private static final int PORT_8081 = 8081;
    private static final int PORT_8083 = 8083;
    public static final String PARAMETER_NAME = "MY_PARAM";
    public static final String PARAMETER_VALUE = "MY_VALUE";
    final EntandoPlugin entandoPlugin = new EntandoPluginBuilder(buildTestEntandoPlugin()).editSpec()
            .withParameters(Collections.singletonMap(PARAMETER_NAME, PARAMETER_VALUE))
            .withNewResourceRequirements()
            .withStorageRequest("8Gi")
            .withStorageLimit("16Gi")
            .withCpuRequest("150m")
            .withCpuLimit("1.5")
            .withFileUploadLimit("150m")
            .withMemoryRequest("1Gi")
            .withMemoryLimit("2Gi")
            .endResourceRequirements()
            .withSecurityLevel(PluginSecurityLevel.LENIENT).endSpec().build();

    @Spy
    private final SimpleK8SClient<EntandoResourceClientDouble> client = new SimpleK8SClientDouble();
    @Mock
    private SimpleKeycloakClient keycloakClient;
    private EntandoPluginController entandoPluginController;

    @BeforeEach
    public void putApp() {
        client.entandoResources().putEntandoPlugin(entandoPlugin);
        client.secrets().overwriteControllerSecret(buildInfrastructureSecret());
        client.secrets().overwriteControllerSecret(buildKeycloakSecret());
        entandoPluginController = new EntandoPluginController(client, keycloakClient);
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, Action.ADDED.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, entandoPlugin.getMetadata().getName());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, entandoPlugin.getMetadata().getNamespace());
    }

    @Test
    public void testSecrets() {
        // Given I have an Entando Plugin with a MySQL Database
        final EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        // And I have a fully deployed KeycloakServer
        //And Keycloak is receiving requests
        when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);

        //When the the EntandoPluginController is notified that a new EntandoPlugin has been added
        entandoPluginController.onStartup(new StartupEvent());

        //Then a K8S Secret was created with a name that reflects the EntandoPlugin and the fact that it is an admin secret
        NamedArgumentCaptor<Secret> adminSecretCaptor = forResourceNamed(Secret.class, MY_PLUGIN_DB_ADMIN_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(newEntandoPlugin), adminSecretCaptor.capture());
        Secret theDbAdminSecret = adminSecretCaptor.getValue();
        assertThat(theKey(KubeUtils.USERNAME_KEY).on(theDbAdminSecret), is("root"));
        assertThat(theKey(KubeUtils.PASSSWORD_KEY).on(theDbAdminSecret), is(not(emptyOrNullString())));
        assertThat(theLabel(ENTANDO_PLUGIN_LABEL_NAME).on(theDbAdminSecret), is(MY_PLUGIN));

        //And a K8S Secret was created with a name that reflects the EntandoPlugin and the fact that it is the plugin's secret
        NamedArgumentCaptor<Secret> pluginSecretCaptor = forResourceNamed(Secret.class, MY_PLUGIN_PLUGINDB_SECRET);
        verify(client.secrets()).createSecretIfAbsent(eq(newEntandoPlugin), pluginSecretCaptor.capture());
        Secret thePluginDbSecret = pluginSecretCaptor.getValue();
        assertThat(theKey(KubeUtils.USERNAME_KEY).on(thePluginDbSecret), is("my_plugin_plugindb"));
        assertThat(theKey(KubeUtils.PASSSWORD_KEY).on(thePluginDbSecret), is(not(emptyOrNullString())));
        assertThat(theLabel(ENTANDO_PLUGIN_LABEL_NAME).on(thePluginDbSecret), is(MY_PLUGIN));

        //Then a K8S Secret was created with a name that reflects the EntandoPlugin and the fact that it is a Keycloak secret
        NamedArgumentCaptor<Secret> keycloakSecretCaptor = forResourceNamed(Secret.class, MY_PLUGIN_SERVER_SECRET);
        verify(client.secrets(), atLeastOnce()).createSecretIfAbsent(eq(newEntandoPlugin), keycloakSecretCaptor.capture());
        Secret keycloakSecret = keycloakSecretCaptor.getValue();
        assertThat(keycloakSecret.getStringData().get(KeycloakClientCreator.CLIENT_ID_KEY), is(MY_PLUGIN_SERVER));
        assertThat(keycloakSecret.getStringData().get(KeycloakClientCreator.CLIENT_SECRET_KEY), is(KEYCLOAK_SECRET));

    }

    @Test
    public void testPersistentVolumeClaims() {
        //Given I have an Entando Plugin with a MySQL Database
        EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        //And that K8S is up and receiving PVC requests
        PersistentVolumeClaimStatus dbPvcStatus = new PersistentVolumeClaimStatus();
        lenient().when(client.persistentVolumeClaims()
                .loadPersistentVolumeClaim(eq(newEntandoPlugin), eq(MY_PLUGIN_DB_PVC)))
                .then(respondWithPersistentVolumeClaimStatus(dbPvcStatus));
        PersistentVolumeClaimStatus serverPvcStatus = new PersistentVolumeClaimStatus();
        lenient().when(client.persistentVolumeClaims()
                .loadPersistentVolumeClaim(eq(newEntandoPlugin), eq(MY_PLUGIN_SERVER_PVC)))
                .then(respondWithPersistentVolumeClaimStatus(serverPvcStatus));

        //When the EntandoPluginController is notified that a new EntandoPlugin has been added
        entandoPluginController.onStartup(new StartupEvent());

        //Then K8S was instructed to create a PersistentVolumeClaim for the DB and the JEE Server
        NamedArgumentCaptor<PersistentVolumeClaim> dbPvcCaptor = forResourceNamed(PersistentVolumeClaim.class,
                MY_PLUGIN_DB_PVC);
        verify(client.persistentVolumeClaims()).createPersistentVolumeClaimIfAbsent(eq(newEntandoPlugin), dbPvcCaptor.capture());
        //With names that reflect the EntandoPlugin and the type of deployment the claim is used for
        final PersistentVolumeClaim theDbPvc = dbPvcCaptor.getValue();

        NamedArgumentCaptor<PersistentVolumeClaim> pluginPvcCaptor = forResourceNamed(PersistentVolumeClaim.class,
                MY_PLUGIN_SERVER_PVC);
        verify(client.persistentVolumeClaims()).createPersistentVolumeClaimIfAbsent(eq(newEntandoPlugin), pluginPvcCaptor.capture());
        PersistentVolumeClaim theServerPvc = pluginPvcCaptor.getValue();
        Quantity storageRequest = theServerPvc.getSpec().getResources().getLimits().get("storage");
        assertThat(storageRequest.getAmount(), is("16"));
        assertThat(storageRequest.getFormat(), is("Gi"));
        Quantity storageLimit = theServerPvc.getSpec().getResources().getRequests().get("storage");
        assertThat(storageLimit.getAmount(), is("8"));
        assertThat(storageLimit.getFormat(), is("Gi"));
        //And labels that link this PVC to the EntandoPlugin and the specific deployment
        assertThat(theLabel(ENTANDO_PLUGIN_LABEL_NAME).on(theDbPvc), is(MY_PLUGIN));
        assertThat(theLabel(DEPLOYMENT_LABEL_NAME).on(theDbPvc), is(MY_PLUGIN_DB));
        assertThat(theLabel(ENTANDO_PLUGIN_LABEL_NAME).on(theServerPvc), is(MY_PLUGIN));
        assertThat(theLabel(DEPLOYMENT_LABEL_NAME).on(theServerPvc), is(MY_PLUGIN_SERVER));

        //And both PersistentVolumeClaims were reloaded from  K8S for its latest state
        verify(client.persistentVolumeClaims()).loadPersistentVolumeClaim(eq(newEntandoPlugin), eq(MY_PLUGIN_DB_PVC));
        verify(client.persistentVolumeClaims()).loadPersistentVolumeClaim(eq(newEntandoPlugin), eq(MY_PLUGIN_SERVER_PVC));
        // And K8S was instructed to update the status of the EntandoPlugin with
        // the status of both PersistentVolumeClaims
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoPlugin), argThat(containsThePersistentVolumeClaimStatus(dbPvcStatus)));
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoPlugin), argThat(containsThePersistentVolumeClaimStatus(serverPvcStatus)));
    }

    @Test
    public void testService() {
        //Given I have an Entando Plugin with a MySQL Database
        EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        //And that K8S is up and receiving Service requests
        ServiceStatus dbServiceStatus = new ServiceStatus();
        lenient().when(client.services().loadService(eq(newEntandoPlugin), eq(MY_PLUGIN_DB_SERVICE)))
                .then(respondWithServiceStatus(dbServiceStatus));
        ServiceStatus serverServiceStatus = new ServiceStatus();
        lenient().when(client.services().loadService(eq(newEntandoPlugin), eq(MY_PLUGIN_SERVER_SERVICE)))
                .then(respondWithServiceStatus(serverServiceStatus));

        //When the the EntandoPluginController is notified that a new EntandoPlugin has been added
        entandoPluginController.onStartup(new StartupEvent());

        //Then K8S was instructed to create a service for both the PLugin JEE Server and the DB
        NamedArgumentCaptor<Service> dbServiceCaptor = forResourceNamed(Service.class, MY_PLUGIN_DB_SERVICE);
        NamedArgumentCaptor<Service> serverServiceCaptor = forResourceNamed(Service.class, MY_PLUGIN_SERVER_SERVICE);
        //With names that reflect the EntandoPlugin and the type of deployment the claim is used for
        verify(client.services()).createOrReplaceService(eq(newEntandoPlugin), dbServiceCaptor.capture());
        verify(client.services()).createOrReplaceService(eq(newEntandoPlugin), serverServiceCaptor.capture());

        //And selectors that match the EntandoApp,EntandoPlugin and the specific deployment specifying the pods
        Service theDbService = dbServiceCaptor.getValue();
        Map<String, String> dbSelector = theDbService.getSpec().getSelector();
        assertThat(dbSelector.get(ENTANDO_PLUGIN_LABEL_NAME), is(MY_PLUGIN));
        assertThat(dbSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_PLUGIN_DB));
        Service thePluginService = serverServiceCaptor.getValue();
        Map<String, String> jeeServerSelector = thePluginService.getSpec().getSelector();
        assertThat(jeeServerSelector.get(ENTANDO_PLUGIN_LABEL_NAME), is(MY_PLUGIN));
        assertThat(jeeServerSelector.get(DEPLOYMENT_LABEL_NAME), is(MY_PLUGIN_SERVER));

        //And the TCP port 3306 named 'db-port' for the DB Service
        assertThat(thePortNamed(DB_PORT).on(theDbService).getPort(), is(PORT_3396));
        assertThat(thePortNamed(DB_PORT).on(theDbService).getProtocol(), is(TCP));
        assertThat(thePortNamed(DB_PORT).on(theDbService).getTargetPort().getIntVal(), is(PORT_3396));

        //And the TCP port 8081 named 'server-port' for the Plugin service
        assertThat(thePortNamed(SERVER_PORT).on(thePluginService).getPort(), is(PORT_8081));
        assertThat(thePortNamed(SERVER_PORT).on(thePluginService).getTargetPort().getIntVal(), is(PORT_8081));
        assertThat(thePortNamed(SERVER_PORT).on(thePluginService).getProtocol(), is(TCP));

        //And the state  of both services was reloaded from K8S
        verify(client.services()).loadService(eq(newEntandoPlugin), eq(MY_PLUGIN_DB_SERVICE));
        verify(client.services()).loadService(eq(newEntandoPlugin), eq(MY_PLUGIN_SERVER_SERVICE));
        //And K8S was instructed to update the status of the EntandoPlugin with the status of the service
        //verify(client).updateStatus(eq(newEntandoPlugin), argThat(matchesServiceStatus(dbServiceStatus)));
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoPlugin), argThat(matchesServiceStatus(serverServiceStatus)));
    }

    @Test
    public void testDeployments() {
        //Given I have configured the controller to use image version 6.0.0 by default
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_DOCKER_IMAGE_VERSION_FALLBACK.getJvmSystemProperty(), "6.0.0");
        //And I have an Entando Plugin with a MySQL Database and a connectionConfig for pam-connection
        EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        // And a secret named pam-connection
        client.secrets().createSecretIfAbsent(newEntandoPlugin, new SecretBuilder()
                .withNewMetadata()
                .withNamespace(newEntandoPlugin.getMetadata().getNamespace())
                .withName("pam-connection")
                .endMetadata()
                .addToData("config.yaml", "base64something")
                .build());
        //And K8S is receiving Deployment requests
        DeploymentStatus dbDeploymentStatus = new DeploymentStatus();
        lenient().when(client.deployments().loadDeployment(eq(newEntandoPlugin), eq(MY_PLUGIN_DB_DEPLOYMENT)))
                .then(respondWithDeploymentStatus(dbDeploymentStatus));
        DeploymentStatus serverDeploymentStatus = new DeploymentStatus();
        lenient().when(client.deployments().loadDeployment(eq(newEntandoPlugin), eq(MY_PLUGIN + "-server-deployment")))
                .then(respondWithDeploymentStatus(serverDeploymentStatus));

        when(keycloakClient.prepareClientAndReturnSecret(any(KeycloakClientConfig.class))).thenReturn(KEYCLOAK_SECRET);

        //When the DeployCommand processes the addition request
        entandoPluginController.onStartup(new StartupEvent());

        //Then K8S was instructed to create a Deployment for both the Plugin JEE Server and the DB
        NamedArgumentCaptor<Deployment> dbDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_PLUGIN_DB_DEPLOYMENT);
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoPlugin), dbDeploymentCaptor.capture());
        final Deployment dbDeployment = dbDeploymentCaptor.getValue();
        NamedArgumentCaptor<Deployment> serverDeploymentCaptor = forResourceNamed(Deployment.class,
                MY_PLUGIN + "-server-deployment");
        verify(client.deployments()).createOrPatchDeployment(eq(newEntandoPlugin), serverDeploymentCaptor.capture());
        final Deployment serverDeployment = serverDeploymentCaptor.getValue();

        //With a Pod Template that has labels linking it to the previously created K8S Services
        assertThat(theLabel(DEPLOYMENT_LABEL_NAME).on(dbDeployment.getSpec().getTemplate()), is(MY_PLUGIN_DB));
        assertThat(theLabel(ENTANDO_PLUGIN_LABEL_NAME).on(dbDeployment.getSpec().getTemplate()), is(MY_PLUGIN));
        assertThat(theLabel(DEPLOYMENT_LABEL_NAME).on(serverDeployment.getSpec().getTemplate()), is(MY_PLUGIN_SERVER));
        assertThat(theLabel(ENTANDO_PLUGIN_LABEL_NAME).on(serverDeployment.getSpec().getTemplate()), is(MY_PLUGIN));
        verifyDbContainer(theContainerNamed("db-container").on(dbDeployment));
        verifyPluginServerContainer(theContainerNamed("server-container").on(serverDeployment));
        // And mapping a persistent volume with a name that reflects the EntandoPlugin
        // and the deployment the Volume is used for
        //That are linked to the previously created PersistentVolumeClaims
        assertThat(theVolumeNamed(MY_PLUGIN + "-db-volume").on(dbDeployment).getPersistentVolumeClaim()
                .getClaimName(), is(MY_PLUGIN_DB_PVC));
        assertThat(theVolumeNamed(MY_PLUGIN + "-server-volume").on(serverDeployment).getPersistentVolumeClaim()
                .getClaimName(), is(MY_PLUGIN_SERVER_PVC));

        // And mapping secret volume for the connectionConfig
        assertThat(theVolumeNamed("pam-connection-volume").on(serverDeployment).getSecret()
                .getSecretName(), is("pam-connection"));

        //And the PluginController logged into Keycloak independently
        verify(keycloakClient, times(2))
                .login(eq(MY_KEYCLOAK_BASE_URL), eq(MY_KEYCLOAK_ADMIN_USERNAME), anyString());

        //And the state of both Deployments was reloaded from K8S
        verify(client.deployments()).loadDeployment(eq(newEntandoPlugin), eq(MY_PLUGIN_DB_DEPLOYMENT));
        verify(client.deployments()).loadDeployment(eq(newEntandoPlugin), eq(MY_PLUGIN_SERVER_DEPLOYMENT));

        //And K8S was instructed to update the status of the EntandoPlugin with the status of the two deployments
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoPlugin), argThat(matchesDeploymentStatus(dbDeploymentStatus)));
        verify(client.entandoResources(), atLeastOnce())
                .updateStatus(eq(newEntandoPlugin), argThat(matchesDeploymentStatus(serverDeploymentStatus)));

        //And all persistent volumes are mapped
        verifyThatAllVolumesAreMapped(newEntandoPlugin, client, dbDeployment);
        verifyThatAllVolumesAreMapped(newEntandoPlugin, client, serverDeployment);
        verifyThatAllVariablesAreMapped(newEntandoPlugin, client, dbDeployment);
        verifyThatAllVariablesAreMapped(newEntandoPlugin, client, serverDeployment);

        verifyServiceAccount(newEntandoPlugin, serverDeployment);

    }

    private void verifyServiceAccount(EntandoCustomResource newEntandoPlugin, Deployment serverDeployment) {
        assertThat(serverDeployment.getSpec().getTemplate().getSpec().getServiceAccountName(), is("entando-plugin"));
        NamedArgumentCaptor<ServiceAccount> serviceAccountCaptor = forResourceNamed(ServiceAccount.class, "entando-plugin");
        verify(client.serviceAccounts()).createServiceAccountIfAbsent(eq(newEntandoPlugin), serviceAccountCaptor.capture());
        NamedArgumentCaptor<Role> roleCaptor = forResourceNamed(Role.class, "entando-plugin");
        verify(client.serviceAccounts()).createRoleIfAbsent(eq(newEntandoPlugin), roleCaptor.capture());
        assertThat(roleCaptor.getValue().getRules().get(0).getResources(), is(Arrays.asList("entandoplugins")));
        assertThat(roleCaptor.getValue().getRules().get(0).getVerbs(), is(Arrays.asList("get", "update")));
        assertThat(roleCaptor.getValue().getRules().get(1).getResources(), is(Arrays.asList("secrets")));
        assertThat(roleCaptor.getValue().getRules().get(1).getVerbs(), is(Arrays.asList("create", "get", "update", "delete")));
        NamedArgumentCaptor<RoleBinding> roleBindingCaptor = forResourceNamed(RoleBinding.class, "entando-plugin-rolebinding");
        verify(client.serviceAccounts()).createRoleBindingIfAbsent(eq(newEntandoPlugin), roleBindingCaptor.capture());
    }

    private void verifyPluginServerContainer(Container thePluginContainer) {
        //Exposing a port 8081 for the JEE Server Container
        assertThat(thePortNamed(SERVER_PORT).on(thePluginContainer).getContainerPort(), is(PORT_8081));
        assertThat(thePortNamed(SERVER_PORT).on(thePluginContainer).getProtocol(), is(TCP));
        // and the JEE volument is mounted at /entando-data
        assertThat(theVolumeMountNamed(MY_PLUGIN + "-server-volume").on(thePluginContainer).getMountPath(),
                is("/entando-data"));
        assertThat(theVolumeMountNamed("pam-connection-volume").on(thePluginContainer).getMountPath(),
                is("/etc/entando/connectionconfigs/pam-connection"));
        //And the JEE container uses an image reflecting the custom registry and Entando image version specified
        assertThat(thePluginContainer.getImage(), is("docker.io/entando/myplugin:6.0.0"));
        KeycloakClientConfigArgumentCaptor keycloakClientConfigCaptor = forClientId(MY_PLUGIN_SERVER);
        verify(keycloakClient).prepareClientAndReturnSecret(keycloakClientConfigCaptor.capture());//1 plugin
        assertThat(keycloakClientConfigCaptor.getValue().getRealm(), is("entando"));
        assertThat(keycloakClientConfigCaptor.getValue().getPermissions().get(0).getRole(), is("plugin-admin"));
        //And is configured to use the previously installed Keycloak instance
        verifyKeycloakSettings(thePluginContainer, MY_PLUGIN_SERVER_SECRET);
        assertThat(
                theVariableReferenceNamed(SPRING_DATASOURCE_USERNAME).on(thePluginContainer).getSecretKeyRef().getName(),
                is(MY_PLUGIN_PLUGINDB_SECRET));
        assertThat(theVariableReferenceNamed(SPRING_DATASOURCE_USERNAME).on(thePluginContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.USERNAME_KEY));
        assertThat(theVariableReferenceNamed(SPRING_DATASOURCE_PASSWORD).on(thePluginContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));
        assertThat(theVariableNamed(PARAMETER_NAME).on(thePluginContainer), is(PARAMETER_VALUE));
        assertThat(theVariableNamed("SPRING_DATASOURCE_URL").on(thePluginContainer),
                is("jdbc:mysql://" + MY_PLUGIN_DB_SERVICE + "." + MY_PLUGIN_NAMESPACE + ".svc.cluster.local:3306/my_plugin_plugindb"));
        assertThat(theVariableNamed("ENTANDO_CONNECTIONS_ROOT").on(thePluginContainer),
                is(DeploymentCreator.ENTANDO_SECRET_MOUNTS_ROOT));
        assertThat(theVariableNamed("ENTANDO_PLUGIN_SECURITY_LEVEL").on(thePluginContainer), is("LENIENT"));
        Quantity cpuRequest = thePluginContainer.getResources().getRequests().get("cpu");
        assertThat(cpuRequest.getAmount(), is("150"));
        assertThat(cpuRequest.getFormat(), is("m"));
        Quantity cpuLimit = thePluginContainer.getResources().getLimits().get("cpu");
        assertThat(cpuLimit.getAmount(), is("1.5"));
        assertThat(cpuLimit.getFormat(), is(""));
        Quantity memoryRequest = thePluginContainer.getResources().getRequests().get("memory");
        assertThat(memoryRequest.getAmount(), is("1"));
        assertThat(memoryRequest.getFormat(), is("Gi"));
        Quantity memoryLimit = thePluginContainer.getResources().getLimits().get("memory");
        assertThat(memoryLimit.getAmount(), is("2"));
        assertThat(memoryLimit.getFormat(), is("Gi"));
    }

    private void verifyDbContainer(Container theDbContainer) {
        //Exposing a port 3306 for the DB Container
        assertThat(thePortNamed(DB_PORT).on(theDbContainer).getContainerPort(), is(PORT_3396));
        assertThat(thePortNamed(DB_PORT).on(theDbContainer).getProtocol(), is(TCP));
        // and the db volume is mounted at //var/lib/mysql/data
        assertThat(theVolumeMountNamed(MY_PLUGIN + "-db-volume").on(theDbContainer).getMountPath(),
                is("/var/lib/mysql/data"));

        //And the db container uses the image reflecting official mysql image
        //Please note: the docker.io and 6.0.0 my seem counter-intuitive, but it indicates that we are
        //actually controlling the image as intended
        //With the correct version in the configmap this will work as planned
        assertThat(theDbContainer.getImage(), is("docker.io/entando/mysql-57-centos7:6.0.0"));

        // And is configured to use the correct username, database and password
        assertThat(theVariableReferenceNamed("MYSQL_ROOT_PASSWORD").on(theDbContainer).getSecretKeyRef().getName(),
                is(MY_PLUGIN_DB_ADMIN_SECRET));
        assertThat(theVariableReferenceNamed("MYSQL_ROOT_PASSWORD").on(theDbContainer).getSecretKeyRef().getKey(),
                is(KubeUtils.PASSSWORD_KEY));
    }

    @Test
    public void testSchemaPreparation() {
        //Given I have an Entando Plugin with a MySQL Database
        EntandoPlugin newEntandoPlugin = this.entandoPlugin;
        //When the EntandoPluginController is notified that a new EntandoPlugin has been added
        entandoPluginController.onStartup(new StartupEvent());

        //Then a Pod  is created that has labels linking it to the previously created EntandoApp
        LabeledArgumentCaptor<Pod> podCaptor = forResourceWithLabel(Pod.class, ENTANDO_PLUGIN_LABEL_NAME, MY_PLUGIN)
                .andWithLabel(KubeUtils.DB_JOB_LABEL_NAME, MY_PLUGIN + "-db-preparation-job");
        verify(client.pods()).runToCompletion(podCaptor.capture());
        Pod pod = podCaptor.getValue();
        verifySchemaCreationFor(MY_PLUGIN_PLUGINDB_SECRET, pod, MY_PLUGIN + "-plugindb-schema-creation-job");
    }

    private void verifySchemaCreationFor(String secretToMatch, Pod pod, String containerName) {
        Container resultingContainer = theInitContainerNamed(containerName).on(pod);
        //And the DB Schema preparation Image is configured with the appropriate Environment Variables
        assertThat(theVariableNamed(DATABASE_NAME).on(resultingContainer), is("my_plugin_db"));
        assertThat(theVariableNamed(DATABASE_SERVER_HOST).on(resultingContainer),
                is(MY_PLUGIN_DB_SERVICE + "." + MY_PLUGIN_NAMESPACE + ".svc.cluster.local"));
        verifyStandardSchemaCreationVariables(MY_PLUGIN_DB_ADMIN_SECRET, secretToMatch, resultingContainer, DbmsVendor.MYSQL);
    }
}
