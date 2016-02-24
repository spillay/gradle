/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.tooling.internal.provider.runner;

import org.gradle.StartParameter;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeContextBuilder;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeScopeServices;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.specs.Spec;
import org.gradle.initialization.*;
import org.gradle.internal.Cast;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.BuildSessionScopeServices;
import org.gradle.launcher.daemon.configuration.DaemonUsage;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.*;
import org.gradle.logging.ProgressLoggerFactory;
import org.gradle.tooling.*;
import org.gradle.tooling.internal.adapter.ProtocolToModelAdapter;
import org.gradle.tooling.internal.consumer.CancellationTokenInternal;
import org.gradle.tooling.internal.consumer.DefaultGradleConnector;
import org.gradle.tooling.internal.protocol.CompositeBuildExceptionVersion1;
import org.gradle.tooling.internal.protocol.eclipse.SetContainer;
import org.gradle.tooling.internal.protocol.eclipse.SetOfEclipseProjects;
import org.gradle.tooling.internal.provider.BuildActionResult;
import org.gradle.tooling.internal.provider.BuildModelAction;
import org.gradle.tooling.internal.provider.PayloadSerializer;
import org.gradle.tooling.internal.provider.connection.CompositeParameters;
import org.gradle.tooling.internal.provider.connection.GradleParticipantBuild;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CompositeBuildModelActionRunner implements CompositeBuildActionRunner {
    private Map<String, Class<? extends HierarchicalElement>> modelRequestTypeToModelTypeMapping = new HashMap<String, Class<? extends HierarchicalElement>>() {{
        this.put(SetOfEclipseProjects.class.getName(), EclipseProject.class);
    }};

    private boolean supportsCompositeSubstitution(Set<BuildEnvironment> buildEnvironments) {
        final GradleVersion minimumVersion = GradleVersion.version("2.11");
        return CollectionUtils.every(buildEnvironments, new Spec<BuildEnvironment>() {
            @Override
            public boolean isSatisfiedBy(BuildEnvironment element) {
                GradleVersion thisVersion = GradleVersion.version(element.getGradle().getGradleVersion());
                return thisVersion.compareTo(minimumVersion) > 0;
            }
        });
    }

    @Override
    public void run(BuildAction action, BuildRequestContext requestContext, CompositeBuildActionParameters actionParameters, CompositeBuildController buildController) {
        if (!(action instanceof BuildModelAction)) {
            return;
        }
        BuildModelAction modelAction = (BuildModelAction) action;
        Set<Object> results = aggregateModels(modelAction, actionParameters, requestContext.getCancellationToken(), buildController.getBuildScopeServices());
        SetContainer setContainer = new SetContainer(results);
        PayloadSerializer payloadSerializer = buildController.getBuildScopeServices().get(PayloadSerializer.class);
        buildController.setResult(new BuildActionResult(payloadSerializer.serialize(setContainer), null));
    }

    private Class<? extends HierarchicalElement> getModelType(BuildModelAction modelAction) {
        final String requestedModelName = modelAction.getModelName();
        Class<? extends HierarchicalElement> modelType = modelRequestTypeToModelTypeMapping.get(requestedModelName);
        if (modelType == null) {
            throw new CompositeBuildExceptionVersion1(new IllegalArgumentException("Unknown model " + requestedModelName));
        }
        return modelType;
    }

    private Set<Object> aggregateModels(BuildModelAction modelAction, CompositeBuildActionParameters actionParameters, BuildCancellationToken cancellationToken, ServiceRegistry buildScopeServices) {
        Set<Object> results = new LinkedHashSet<Object>();
        final CompositeParameters compositeParameters = actionParameters.getCompositeParameters();

        ProgressLoggerFactory progressLoggerFactory = buildScopeServices.get(ProgressLoggerFactory.class);

        final List<GradleParticipantBuild> participantBuilds = compositeParameters.getBuilds();
        final Set<BuildEnvironment> buildEnvironments = fetchModelsViaToolingAPI(BuildEnvironment.class, participantBuilds, compositeParameters, cancellationToken, progressLoggerFactory);
        Class<? extends HierarchicalElement> modelType = getModelType(modelAction);
        if (supportsCompositeSubstitution(buildEnvironments)) {
            results.addAll(fetchCompositeModelsInProcess(modelAction, modelType, compositeParameters.getBuilds(), cancellationToken, buildScopeServices));
        } else {
            results.addAll(fetchModelsViaToolingAPI(modelType, compositeParameters.getBuilds(), compositeParameters, cancellationToken, progressLoggerFactory));
        }
        return results;
    }

    private <T> Set<T> fetchModelsViaToolingAPI(Class<T> modelType, List<GradleParticipantBuild> participantBuilds, CompositeParameters compositeParameters, BuildCancellationToken cancellationToken, ProgressLoggerFactory progressLoggerFactory) {
        final Set<T> results = new LinkedHashSet<T>();
        for (GradleParticipantBuild participant : participantBuilds) {
            if (cancellationToken.isCancellationRequested()) {
                break;
            }
            ProjectConnection projectConnection = connect(participant, compositeParameters);
            ModelBuilder<T> modelBuilder = projectConnection.model(modelType);
            modelBuilder.withCancellationToken(new CancellationTokenAdapter(cancellationToken));
            modelBuilder.addProgressListener(new ProgressListenerToProgressLoggerAdapter(progressLoggerFactory));
            if (cancellationToken.isCancellationRequested()) {
                projectConnection.close();
                break;
            }
            try {
                accumulateModels(results, modelBuilder.get());
            } catch (GradleConnectionException e) {
                throw new CompositeBuildExceptionVersion1(e);
            } finally {
                projectConnection.close();
            }
        }
        return results;
    }

    private CompositeBuildContext constructCompositeContext(GradleLauncherFactory gradleLauncherFactory, List<GradleParticipantBuild> participantBuilds) {
        CompositeContextBuilder builder = new CompositeContextBuilder(gradleLauncherFactory);
        for (GradleParticipantBuild participant : participantBuilds) {
            final String participantName = participant.getProjectDir().getName();
            builder.addParticipant(participantName, participant.getProjectDir());
        }
        return builder.build();
    }

    private <T extends HierarchicalElement> Set<T> fetchCompositeModelsInProcess(BuildModelAction modelAction, Class<T> modelType, List<GradleParticipantBuild> participantBuilds,
                                                                                 BuildCancellationToken cancellationToken, ServiceRegistry sharedServices) {
        final Set<T> results = new LinkedHashSet<T>();

        GradleLauncherFactory gradleLauncherFactory = sharedServices.get(GradleLauncherFactory.class);
        CompositeBuildContext context = constructCompositeContext(gradleLauncherFactory, participantBuilds);

        DefaultServiceRegistry compositeServices = (DefaultServiceRegistry) ServiceRegistryBuilder.builder()
            .displayName("Composite services")
            .parent(sharedServices)
            .build();
        compositeServices.add(CompositeBuildContext.class, context);
        compositeServices.addProvider(new CompositeScopeServices(modelAction.getStartParameter(), compositeServices));

        BuildActionRunner runner = new NonSerializingBuildModelActionRunner();
        BuildActionExecuter<BuildActionParameters> buildActionExecuter = new InProcessBuildActionExecuter(gradleLauncherFactory, runner);
        DefaultBuildRequestContext requestContext = new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(System.currentTimeMillis()), cancellationToken, new NoOpBuildEventConsumer());

        ProtocolToModelAdapter protocolToModelAdapter = new ProtocolToModelAdapter();

        for (GradleParticipantBuild participant : participantBuilds) {
            DefaultBuildActionParameters actionParameters = new DefaultBuildActionParameters(Collections.EMPTY_MAP, Collections.<String, String>emptyMap(), participant.getProjectDir(), LogLevel.INFO, DaemonUsage.EXPLICITLY_DISABLED, false, true, ClassPath.EMPTY);

            StartParameter startParameter = modelAction.getStartParameter().newInstance();
            startParameter.setProjectDir(participant.getProjectDir());

            ServiceRegistry buildScopedServices = new BuildSessionScopeServices(compositeServices, startParameter, ClassPath.EMPTY);

            BuildModelAction mappedAction = new BuildModelAction(startParameter, modelType.getName(), modelAction.isRunTasks(), modelAction.getClientSubscriptions());

            Object result = buildActionExecuter.execute(mappedAction, requestContext, actionParameters, buildScopedServices);
            T castResult = protocolToModelAdapter.adapt(modelType, result);
            try {
                accumulateModels(results, castResult);
            } catch (GradleConnectionException e) {
                throw new CompositeBuildExceptionVersion1(e);
            }
        }
        return results;
    }

    private <T> void accumulateModels(Set<T> allResults, T element) {
        allResults.add(Cast.<T>uncheckedCast(element));
        if (element instanceof HierarchicalElement) {
            for (Object child : ((HierarchicalElement) element).getChildren().getAll()) {
                T o = Cast.uncheckedCast(child);
                accumulateModels(allResults, o);
            }
        }
    }

    private ProjectConnection connect(GradleParticipantBuild build, CompositeParameters compositeParameters) {
        DefaultGradleConnector connector = getInternalConnector();
        File gradleUserHomeDir = compositeParameters.getGradleUserHomeDir();
        File daemonBaseDir = compositeParameters.getDaemonBaseDir();
        Integer daemonMaxIdleTimeValue = compositeParameters.getDaemonMaxIdleTimeValue();
        TimeUnit daemonMaxIdleTimeUnits = compositeParameters.getDaemonMaxIdleTimeUnits();
        Boolean embeddedParticipants = compositeParameters.isEmbeddedParticipants();

        if (gradleUserHomeDir != null) {
            connector.useGradleUserHomeDir(gradleUserHomeDir);
        }
        if (daemonBaseDir != null) {
            connector.daemonBaseDir(daemonBaseDir);
        }
        if (daemonMaxIdleTimeValue != null && daemonMaxIdleTimeUnits != null) {
            connector.daemonMaxIdleTime(daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits);
        }
        connector.searchUpwards(false);
        connector.forProjectDirectory(build.getProjectDir());

        if (embeddedParticipants) {
            connector.embedded(true);
            connector.useClasspathDistribution();
            return connector.connect();
        } else {
            return configureDistribution(connector, build).connect();
        }
    }

    private DefaultGradleConnector getInternalConnector() {
        return (DefaultGradleConnector) GradleConnector.newConnector();
    }

    private GradleConnector configureDistribution(GradleConnector connector, GradleParticipantBuild build) {
        if (build.getGradleDistribution() == null) {
            if (build.getGradleHome() == null) {
                if (build.getGradleVersion() == null) {
                    connector.useBuildDistribution();
                } else {
                    connector.useGradleVersion(build.getGradleVersion());
                }
            } else {
                connector.useInstallation(build.getGradleHome());
            }
        } else {
            connector.useDistribution(build.getGradleDistribution());
        }

        return connector;
    }

    private final static class CancellationTokenAdapter implements CancellationToken, CancellationTokenInternal {
        private final BuildCancellationToken token;

        private CancellationTokenAdapter(BuildCancellationToken token) {
            this.token = token;
        }

        public boolean isCancellationRequested() {
            return token.isCancellationRequested();
        }

        public BuildCancellationToken getToken() {
            return token;
        }
    }

}
