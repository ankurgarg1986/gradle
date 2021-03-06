/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.tooling.internal.provider;

import org.gradle.StartParameter;
import org.gradle.api.logging.LogLevel;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.BuildEventConsumer;
import org.gradle.initialization.BuildLayoutParameters;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.initialization.DefaultBuildRequestContext;
import org.gradle.initialization.DefaultBuildRequestMetaData;
import org.gradle.initialization.NoOpBuildEventConsumer;
import org.gradle.internal.Factory;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.launcher.cli.converter.LayoutToPropertiesConverter;
import org.gradle.launcher.cli.converter.PropertiesToDaemonParametersConverter;
import org.gradle.launcher.daemon.client.DaemonClientBuildActionExecuter;
import org.gradle.launcher.daemon.client.DaemonClient;
import org.gradle.launcher.daemon.client.DaemonClientFactory;
import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.logging.LoggingManagerInternal;
import org.gradle.logging.LoggingServiceRegistry;
import org.gradle.logging.internal.OutputEventListener;
import org.gradle.logging.internal.OutputEventRenderer;
import org.gradle.process.internal.streams.SafeStreams;
import org.gradle.tooling.internal.build.DefaultBuildEnvironment;
import org.gradle.tooling.internal.consumer.versioning.ModelMapping;
import org.gradle.tooling.internal.protocol.BuildProgressListenerVersion1;
import org.gradle.tooling.internal.protocol.FailureVersion1;
import org.gradle.tooling.internal.protocol.InternalBuildAction;
import org.gradle.tooling.internal.protocol.InternalBuildEnvironment;
import org.gradle.tooling.internal.protocol.ModelIdentifier;
import org.gradle.tooling.internal.protocol.TestDescriptorVersion1;
import org.gradle.tooling.internal.protocol.TestProgressEventVersion1;
import org.gradle.tooling.internal.protocol.TestResultVersion1;
import org.gradle.tooling.internal.provider.connection.ProviderConnectionParameters;
import org.gradle.tooling.internal.provider.connection.ProviderOperationParameters;
import org.gradle.util.GradleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProviderConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderConnection.class);
    private final PayloadSerializer payloadSerializer;
    private final LoggingServiceRegistry loggingServices;
    private final DaemonClientFactory daemonClientFactory;
    private final BuildActionExecuter<BuildActionParameters> embeddedExecutor;

    public ProviderConnection(LoggingServiceRegistry loggingServices, DaemonClientFactory daemonClientFactory, BuildActionExecuter<BuildActionParameters> embeddedExecutor, PayloadSerializer payloadSerializer) {
        this.loggingServices = loggingServices;
        this.daemonClientFactory = daemonClientFactory;
        this.embeddedExecutor = embeddedExecutor;
        this.payloadSerializer = payloadSerializer;
    }

    public void configure(ProviderConnectionParameters parameters) {
        LogLevel providerLogLevel = parameters.getVerboseLogging() ? LogLevel.DEBUG : LogLevel.INFO;
        LOGGER.debug("Configuring logging to level: {}", providerLogLevel);
        LoggingManagerInternal loggingManager = loggingServices.newInstance(LoggingManagerInternal.class);
        loggingManager.setLevel(providerLogLevel);
        loggingManager.start();
    }

    public Object run(String modelName, BuildCancellationToken cancellationToken, ProviderOperationParameters providerParameters) {
        List<String> tasks = providerParameters.getTasks();
        if (modelName.equals(ModelIdentifier.NULL_MODEL) && tasks == null) {
            throw new IllegalArgumentException("No model type or tasks specified.");
        }
        Parameters params = initParams(providerParameters);
        Class<?> type = new ModelMapping().getProtocolTypeFromModelName(modelName);
        if (type == InternalBuildEnvironment.class) {
            //we don't really need to launch the daemon to acquire information needed for BuildEnvironment
            if (tasks != null) {
                throw new IllegalArgumentException("Cannot run tasks and fetch the build environment model.");
            }
            return new DefaultBuildEnvironment(
                    params.gradleUserhome,
                    GradleVersion.current().getVersion(),
                    params.daemonParams.getEffectiveJavaHome(),
                    params.daemonParams.getEffectiveJvmArgs());
        }

        StartParameter startParameter = new ProviderStartParameterConverter().toStartParameter(providerParameters, params.properties);
        BuildProgressListenerVersion1 buildProgressListener = providerParameters.getBuildProgressListener(null);
        boolean listenToTestProgress = buildProgressListener != null && buildProgressListener.getSubscribedEvents().contains(BuildProgressListenerVersion1.TEST_PROGRESS);
        BuildEventConsumer buildEventConsumer = listenToTestProgress ? new BuildProgressListenerInvokingBuildEventConsumer(buildProgressListener) : new NoOpBuildEventConsumer();
        BuildAction action = new BuildModelAction(startParameter, modelName, tasks != null, listenToTestProgress);
        return run(action, cancellationToken, buildEventConsumer, providerParameters, params);
    }

    public Object run(InternalBuildAction<?> clientAction, BuildCancellationToken cancellationToken, ProviderOperationParameters providerParameters) {
        SerializedPayload serializedAction = payloadSerializer.serialize(clientAction);
        Parameters params = initParams(providerParameters);
        StartParameter startParameter = new ProviderStartParameterConverter().toStartParameter(providerParameters, params.properties);
        NoOpBuildEventConsumer buildEventConsumer = new NoOpBuildEventConsumer();
        BuildAction action = new ClientProvidedBuildAction(startParameter, serializedAction);
        return run(action, cancellationToken, buildEventConsumer, providerParameters, params);
    }

    private Object run(BuildAction action, BuildCancellationToken cancellationToken, BuildEventConsumer buildEventConsumer, ProviderOperationParameters providerParameters, Parameters parameters) {
        BuildActionExecuter<ProviderOperationParameters> executer = createExecuter(providerParameters, parameters);
        BuildRequestContext buildRequestContext = new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(providerParameters.getStartTime()), cancellationToken, buildEventConsumer);
        BuildActionResult result = (BuildActionResult) executer.execute(action, buildRequestContext, providerParameters);
        if (result.failure != null) {
            throw (RuntimeException) payloadSerializer.deserialize(result.failure);
        }
        return payloadSerializer.deserialize(result.result);
    }

    private BuildActionExecuter<ProviderOperationParameters> createExecuter(ProviderOperationParameters operationParameters, Parameters params) {
        LoggingServiceRegistry loggingServices;
        BuildActionExecuter<BuildActionParameters> executer;
        if (Boolean.TRUE.equals(operationParameters.isEmbedded())) {
            loggingServices = this.loggingServices;
            executer = embeddedExecutor;
        } else {
            loggingServices = LoggingServiceRegistry.newNestedLogging();
            loggingServices.get(OutputEventRenderer.class).configure(operationParameters.getBuildLogLevel());
            ServiceRegistry clientServices = daemonClientFactory.createBuildClientServices(loggingServices.get(OutputEventListener.class), params.daemonParams, operationParameters.getStandardInput(SafeStreams.emptyInput()));
            executer = new DaemonClientBuildActionExecuter(clientServices.get(DaemonClient.class));
        }
        Factory<LoggingManagerInternal> loggingManagerFactory = loggingServices.getFactory(LoggingManagerInternal.class);
        return new LoggingBridgingBuildActionExecuter(new DaemonBuildActionExecuter(executer, params.daemonParams), loggingManagerFactory);
    }

    private Parameters initParams(ProviderOperationParameters operationParameters) {
        BuildLayoutParameters layout = new BuildLayoutParameters();
        if (operationParameters.getGradleUserHomeDir() != null) {
            layout.setGradleUserHomeDir(operationParameters.getGradleUserHomeDir());
        }
        layout.setSearchUpwards(operationParameters.isSearchUpwards() != null ? operationParameters.isSearchUpwards() : true);
        layout.setProjectDir(operationParameters.getProjectDir());

        Map<String, String> properties = new HashMap<String, String>();
        new LayoutToPropertiesConverter().convert(layout, properties);

        DaemonParameters daemonParams = new DaemonParameters(layout);
        new PropertiesToDaemonParametersConverter().convert(properties, daemonParams);
        if (operationParameters.getDaemonBaseDir(null) != null) {
            daemonParams.setBaseDir(operationParameters.getDaemonBaseDir(null));
        }

        //override the params with the explicit settings provided by the tooling api
        List<String> defaultJvmArgs = daemonParams.getAllJvmArgs();
        daemonParams.setJvmArgs(operationParameters.getJvmArguments(defaultJvmArgs));
        File defaultJavaHome = daemonParams.getEffectiveJavaHome();
        daemonParams.setJavaHome(operationParameters.getJavaHome(defaultJavaHome));

        if (operationParameters.getDaemonMaxIdleTimeValue() != null && operationParameters.getDaemonMaxIdleTimeUnits() != null) {
            int idleTimeout = (int) operationParameters.getDaemonMaxIdleTimeUnits().toMillis(operationParameters.getDaemonMaxIdleTimeValue());
            daemonParams.setIdleTimeout(idleTimeout);
        }

        return new Parameters(daemonParams, properties, layout.getGradleUserHomeDir());
    }

    private static class Parameters {
        DaemonParameters daemonParams;
        Map<String, String> properties;
        File gradleUserhome;

        public Parameters(DaemonParameters daemonParams, Map<String, String> properties, File gradleUserhome) {
            this.daemonParams = daemonParams;
            this.properties = properties;
            this.gradleUserhome = gradleUserhome;
        }
    }

    private static final class BuildProgressListenerInvokingBuildEventConsumer implements BuildEventConsumer {

        private final BuildProgressListenerVersion1 buildProgressListener;

        private BuildProgressListenerInvokingBuildEventConsumer(BuildProgressListenerVersion1 buildProgressListener) {
            this.buildProgressListener = buildProgressListener;
        }

        @Override
        public void dispatch(Object event) {
            if (event instanceof InternalTestProgressEvent) {
                final InternalTestProgressEvent testProgressEvent = (InternalTestProgressEvent) event;
                this.buildProgressListener.onEvent(new TestProgressEventVersion1() {

                    @Override
                    public String getTestStructure() {
                        return testProgressEvent.getTestStructure();
                    }

                    @Override
                    public String getTestOutcome() {
                        return testProgressEvent.getTestOutcome();
                    }

                    @Override
                    public long getEventTime() {
                        return testProgressEvent.getEventTime();
                    }

                    @Override
                    public TestDescriptorVersion1 getDescriptor() {
                        return new TestDescriptorVersion1() {

                            @Override
                            public Object getId() {
                                return testProgressEvent.getDescriptor().getId();
                            }

                            @Override
                            public String getName() {
                                return testProgressEvent.getDescriptor().getName();
                            }

                            @Override
                            public String getClassName() {
                                return testProgressEvent.getDescriptor().getClassName();
                            }

                            @Override
                            public Object getParentId() {
                                return testProgressEvent.getDescriptor().getParentId();
                            }

                        };
                    }

                    @Override
                    public TestResultVersion1 getResult() {
                        return new TestResultVersion1() {

                            @Override
                            public long getStartTime() {
                                return testProgressEvent.getResult().getStartTime();
                            }

                            @Override
                            public long getEndTime() {
                                return testProgressEvent.getResult().getEndTime();
                            }

                            @Override
                            public List<FailureVersion1> getFailures() {
                                List<InternalFailure> resultFailures = testProgressEvent.getResult().getFailures();
                                ArrayList<FailureVersion1> failures = new ArrayList<FailureVersion1>(resultFailures.size());
                                for (final InternalFailure resultFailure : resultFailures) {
                                    failures.add(toFailure(resultFailure));
                                }
                                return failures;
                            }

                        };
                    }

                });
            }
        }

        private static FailureVersion1 toFailure(final InternalFailure resultFailure) {
            if (resultFailure==null) {
                return null;
            }
            return new FailureVersion1() {
                @Override
                public String getMessage() {
                    return resultFailure.getMessage();
                }

                @Override
                public String getDescription() {
                    return resultFailure.getDescription();
                }

                @Override
                public FailureVersion1 getCause() {
                    return toFailure(resultFailure.getCause());
                }
            };
        }

    }

}
