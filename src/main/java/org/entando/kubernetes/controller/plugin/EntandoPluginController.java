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

import static java.lang.String.format;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvider;
import org.entando.kubernetes.controller.spi.capability.CapabilityProvisioningResult;
import org.entando.kubernetes.controller.spi.client.KubernetesClientForControllers;
import org.entando.kubernetes.controller.spi.command.DeploymentProcessor;
import org.entando.kubernetes.controller.spi.common.EntandoControllerException;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.container.ProvidedDatabaseCapability;
import org.entando.kubernetes.controller.spi.container.ProvidedSsoCapability;
import org.entando.kubernetes.controller.spi.deployable.SsoConnectionInfo;
import org.entando.kubernetes.controller.spi.result.DatabaseConnectionInfo;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.capability.CapabilityRequirementBuilder;
import org.entando.kubernetes.model.capability.CapabilityScope;
import org.entando.kubernetes.model.capability.StandardCapability;
import org.entando.kubernetes.model.capability.StandardCapabilityImplementation;
import org.entando.kubernetes.model.common.DbmsVendor;
import org.entando.kubernetes.model.common.EntandoControllerFailure;
import org.entando.kubernetes.model.common.ServerStatus;
import org.entando.kubernetes.model.plugin.EntandoPlugin;
import picocli.CommandLine;

@CommandLine.Command()
public class EntandoPluginController implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(EntandoPluginController.class.getName());
    public static final String DBMS_SECRET_USERNAME_KEY = "username";
    private final KubernetesClientForControllers k8sClient;
    private final CapabilityProvider capabilityProvider;
    private final DeploymentProcessor deploymentProcessor;
    private EntandoPlugin entandoPlugin;

    @Inject
    public EntandoPluginController(KubernetesClientForControllers k8sClient, DeploymentProcessor deploymentProcessor,
            CapabilityProvider capabilityProvider) {
        this.k8sClient = k8sClient;
        this.capabilityProvider = capabilityProvider;
        this.deploymentProcessor = deploymentProcessor;
    }

    @Override
    public void run() {
        fixImposeLimitsDefault();

        entandoPlugin = (EntandoPlugin) k8sClient.resolveCustomResourceToProcess(
                Collections.singletonList(EntandoPlugin.class));
        EntandoPluginServerDeployable deployable;
        try {
            this.entandoPlugin = k8sClient.deploymentStarted(entandoPlugin);
            final DatabaseConnectionInfo dbConnectionInfo = provideDatabaseIfRequired();
            final SsoConnectionInfo ssoConnectionInfo = provideSso();

            var pluginDbmsSecretName = EntandoPluginServerDeployable.mkPlugingSecretName(entandoPlugin);

            // If it's an update we force the schema name to match the one used
            // by the original installation
            var schemaNameOverride = getCurrentUserNameFromSecret(pluginDbmsSecretName);

            deployable = new EntandoPluginServerDeployable(
                    dbConnectionInfo,
                    ssoConnectionInfo,
                    entandoPlugin,
                    pluginDbmsSecretName,
                    schemaNameOverride);

            this.deploymentProcessor.processDeployable(deployable, calculateDbAwareTimeout());
            this.entandoPlugin = k8sClient.deploymentEnded(entandoPlugin);

        } catch (Exception e) {
            try {
                entandoPlugin = k8sClient.deploymentFailed(entandoPlugin, e, NameUtils.MAIN_QUALIFIER);
                LOGGER.log(Level.SEVERE, e, () -> format("EntandoPluginController failed:%n%s",
                        entandoPlugin.getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER)
                                .flatMap(ServerStatus::getEntandoControllerFailure)
                                .orElseThrow(IllegalStateException::new)
                                .getDetailMessage()));
            } catch (Exception ignored) {
                LOGGER.log(Level.SEVERE, e, () -> "EntandoPluginController failed: <<UNABLE TO EXTRACT THE STATE>>");
            }
        }
        var failedServerStatus = entandoPlugin.getStatus().findFailedServerStatus();
        failedServerStatus.flatMap(ServerStatus::getEntandoControllerFailure).ifPresent(s -> {
            printFailureReport(s);
            cleanupFailedPluginInstallation(failedServerStatus.get());
            throw new CommandLine.ExecutionException(new CommandLine(this),
                    "Error starting the plugin pod");
        });
    }

    /**
     * This is a workaround that fixes the default value of the "impose limits" setting but only for the bundle plugins
     * In the near future the fix will be propagated the rest of the infrastructure and this workaround will be removed.
     */
    private void fixImposeLimitsDefault() {
        var current = EntandoOperatorConfigBase.lookupProperty(
                EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMPOSE_LIMITS).map(Boolean::valueOf);
        if (current.isEmpty()) {
            System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_IMPOSE_LIMITS.toString(), "false");
        }
    }

    /**
     * Returns the username of the current plugin installation.
     * <p>
     * The function would find something only if there is an active or failed plugin installation,
     * which means that it's usually called during an update process or an installation run after
     * a failure which left intact the database and the credential secrets of the plugin.
     * </p>
     * @param pluginDbmsSecretName the name of the plugin that stores the username
     * @return the current username of null if not found
     */
    private String getCurrentUserNameFromSecret(String pluginDbmsSecretName) {
        try {
            var secRes = k8sClient.getSecretByName(pluginDbmsSecretName);
            var sec = (secRes != null) ? secRes.get() : null;
            if (sec != null) {
                return sec.getData().compute(DBMS_SECRET_USERNAME_KEY,
                        (k, v) -> new String(Base64.getDecoder().decode(v), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e,
                    () -> "Error analysing the plugin secret of name \"pluginDbmsSecretName\" during"
            );
        }
        return null;
    }

    private void cleanupFailedPluginInstallation(ServerStatus failedServerStatus) {
        var dpn = failedServerStatus.getDeploymentName().orElse("");
        if (dpn.length() == 0) {
            throw new CommandLine.ExecutionException(new CommandLine(this),
                    "Unable to extract from the custom resource the status of the failed server (C1)");
        }
        var ns = entandoPlugin.getMetadata().getNamespace();
        k8sClient.getDeploymentByName(dpn, ns).scale(0);
    }

    static final String STDOUT_SEPARATOR;

    static {
        var tmp = "##########";
        STDOUT_SEPARATOR = "\033[41m\033[1;97m"
                + tmp + tmp + tmp + tmp + tmp + tmp + tmp + tmp + tmp + tmp + tmp + tmp
                + "\033[0;39m"
        ;
    }

    private void printFailureReport(EntandoControllerFailure s) {
        try {
            final var name = s.getFailedObjectName();
            final var ns = s.getFailedObjectNamespace();
            directPrint("");
            directPrint(STDOUT_SEPARATOR);
            directPrint(STDOUT_SEPARATOR);
            directPrint(STDOUT_SEPARATOR);
            directPrint("### ERROR STARTING THE PLUGIN POD");
            directPrint("### the plugin pod \"" + ns + "/" + name + "\" failed to start");
            directPrint("");
            directPrint("### This is its log:");
            directPrint("");
            var failedPod = k8sClient.getPodByName(name, ns);
            directPrint(failedPod.tailingLines(300).getLog());
            directPrint("");
            directPrint(STDOUT_SEPARATOR);
            directPrint(STDOUT_SEPARATOR);
            directPrint(STDOUT_SEPARATOR);
            directPrint("");
        } catch (Exception e) {
            directPrint("<<Unable to extract the failed pod log>>");
            e.printStackTrace(); //NOSONAR
        }
    }

    private static void directPrint(String str) {
        System.err.println(str); //NOSONAR
    }

    private int calculateDbAwareTimeout() {
        final int timeoutForDbAware;
        if (requiresDbmsService(entandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE))) {
            timeoutForDbAware =
                    EntandoOperatorSpiConfig.getPodCompletionTimeoutSeconds()
                            + EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        } else {
            timeoutForDbAware = EntandoOperatorSpiConfig.getPodReadinessTimeoutSeconds();
        }
        return timeoutForDbAware;
    }

    private ProvidedDatabaseCapability provideDatabaseIfRequired() throws TimeoutException {
        final DbmsVendor dbmsVendor = entandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE);
        if (requiresDbmsService(dbmsVendor)) {
            final CapabilityProvisioningResult capabilityResult = capabilityProvider
                    .provideCapability(entandoPlugin, new CapabilityRequirementBuilder()
                            .withCapability(StandardCapability.DBMS)
                            .withImplementation(StandardCapabilityImplementation.valueOf(dbmsVendor.name()))
                            .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.DEDICATED, CapabilityScope.CLUSTER)
                            .build(), 180);
            capabilityResult.getProvidedCapability().getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER)
                    .ifPresent(s ->
                            this.entandoPlugin = this.k8sClient.updateStatus(
                                    entandoPlugin,
                                    new ServerStatus(NameUtils.DB_QUALIFIER, s)
                            ));
            capabilityResult.getControllerFailure().ifPresent(f -> {
                throw new EntandoControllerException(
                        format("Could not prepare database for EntandoPlugin %s/%s%n%s",
                                entandoPlugin.getMetadata().getNamespace(),
                                entandoPlugin.getMetadata().getName(),
                                f.getDetailMessage()));
            });
            return new ProvidedDatabaseCapability(capabilityResult);
        } else {
            return null;
        }
    }

    private boolean requiresDbmsService(DbmsVendor dbmsVendor) {
        return !Set.of(DbmsVendor.NONE, DbmsVendor.EMBEDDED).contains(dbmsVendor);
    }

    private ProvidedSsoCapability provideSso() throws TimeoutException {
        final CapabilityProvisioningResult capabilityResult = capabilityProvider
                .provideCapability(entandoPlugin, new CapabilityRequirementBuilder()
                        .withCapability(StandardCapability.SSO)
                        .withPreferredDbms(determineDbmsForSso())
                        .withPreferredIngressHostName(entandoPlugin.getSpec().getIngressHostName().orElse(null))
                        .withPreferredTlsSecretName(entandoPlugin.getSpec().getTlsSecretName().orElse(null))
                        .withResolutionScopePreference(CapabilityScope.NAMESPACE, CapabilityScope.CLUSTER)
                        .build(), 240);
        capabilityResult.getProvidedCapability().getStatus().getServerStatus(NameUtils.MAIN_QUALIFIER).ifPresent(s ->
                this.entandoPlugin = this.k8sClient.updateStatus(entandoPlugin, new ServerStatus(NameUtils.SSO_QUALIFIER, s)));
        capabilityResult.getControllerFailure().ifPresent(f -> {
            throw new EntandoControllerException(format("Could not prepare SSO for EntandoPlugin %s/%s%n%s", entandoPlugin
                            .getMetadata().getNamespace(), entandoPlugin
                            .getMetadata().getName(),
                    f.getDetailMessage()));
        });
        return new ProvidedSsoCapability(capabilityResult);
    }

    private DbmsVendor determineDbmsForSso() {
        final DbmsVendor dbmsVendor = entandoPlugin.getSpec().getDbms().orElse(DbmsVendor.NONE);
        if (dbmsVendor == DbmsVendor.NONE) {
            return DbmsVendor.EMBEDDED;
        }
        return dbmsVendor;
    }
}
