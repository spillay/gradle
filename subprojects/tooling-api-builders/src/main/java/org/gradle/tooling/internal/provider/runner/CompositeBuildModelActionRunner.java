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

import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Transformer;
import org.gradle.api.specs.Spec;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.internal.Cast;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.launcher.exec.CompositeBuildActionParameters;
import org.gradle.launcher.exec.CompositeBuildActionRunner;
import org.gradle.launcher.exec.CompositeBuildController;
import org.gradle.tooling.*;
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
import org.gradle.tooling.model.GradleModuleVersion;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.gradle.GradlePublication;
import org.gradle.tooling.model.gradle.ProjectPublications;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GFileUtils;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CompositeBuildModelActionRunner implements CompositeBuildActionRunner {
    private Map<String, Class<? extends HierarchicalElement>> modelRequestTypeToModelTypeMapping = new HashMap<String, Class<? extends HierarchicalElement>>() {{
        this.put(SetOfEclipseProjects.class.getName(), EclipseProject.class);
    }};

    class CompositeContext {
        private final Map<String, String> mappings = Maps.newHashMap();
        private final Map<String, Set<String>> dependencies = Maps.newHashMap();
    }

    private CompositeContext buildContext(List<GradleParticipantBuild> participantBuilds, Set<GradleProject> gradleProjects, Set<ProjectPublications> publications) {
        assert gradleProjects.size() == publications.size() && participantBuilds.size()==gradleProjects.size(); // TODO: doesn't support multi-project builds yet
        CompositeContext context = new CompositeContext();
        // TODO: Don't rely on particular ordering
        Iterator<GradleParticipantBuild> participantBuildIterator = participantBuilds.iterator();
        Iterator<GradleProject> gradleProjectsIterator = gradleProjects.iterator();
        Iterator<ProjectPublications> projectPublicationsIterator = publications.iterator();
        while (gradleProjectsIterator.hasNext()) {
            GradleParticipantBuild participantBuild = participantBuildIterator.next();
            String participantBuildName = participantBuild.getProjectDir().getName(); // TODO: Give participant builds names
            GradleProject gradleProject = gradleProjectsIterator.next();
            ProjectPublications projectPublications = projectPublicationsIterator.next();
            for (GradlePublication publication : projectPublications.getPublications().getAll()) {
                GradleModuleVersion gradleModuleVersion = publication.getId();
                String module = gradleModuleVersion.getGroup() + ":" + gradleModuleVersion.getName();
                context.mappings.put(module, participantBuildName + ":" + gradleProject.getPath());
                System.out.println("Got dependencies for " + module + " : " + publication.getDependencies());
                context.dependencies.put(module, CollectionUtils.collect(publication.getDependencies(), new Transformer<String, GradleModuleVersion>() {
                    @Override
                    public String transform(GradleModuleVersion gradleModuleVersion) {
                        return gradleModuleVersion.getGroup() + ":" + gradleModuleVersion.getName() + ":" + gradleModuleVersion.getVersion();
                    }
                }));
            }
        }

        return context;
    }

    private File generateInitScriptFromContext(CompositeContext context) {
        if (context!=null) {
            // TODO: Hack, goes away when we start registering these directly in the build
            File initScript = new File("/tmp/init.gradle");
            StringBuilder sb = new StringBuilder();
            sb.append("def context = services.get(org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext)\n");
            for (Map.Entry<String, String> e : context.mappings.entrySet()) {
                Set<String> deps = context.dependencies.get(e.getKey());
                String dependencyList = deps.isEmpty() ? "" :  "'" + StringUtils.join(deps, "', '") + "'";
                String registration = String.format("context.register('%s', '%s', [%s])", e.getKey(), e.getValue(), dependencyList);
                sb.append(registration).append("\n");
            }
            GFileUtils.writeStringToFile(initScript, sb.toString());
            return initScript;
        }
        return null;
    }

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
        final String requestedModelName = ((BuildModelAction) action).getModelName();
        Class<? extends HierarchicalElement> modelType = modelRequestTypeToModelTypeMapping.get(requestedModelName);
        if (modelType != null) {
            Set<Object> results = aggregateModels(modelType, actionParameters, requestContext.getCancellationToken());
            SetContainer setContainer = new SetContainer(results);
            PayloadSerializer payloadSerializer = buildController.getBuildScopeServices().get(PayloadSerializer.class);
            buildController.setResult(new BuildActionResult(payloadSerializer.serialize(setContainer), null));
        } else {
            throw new CompositeBuildExceptionVersion1(new IllegalArgumentException("Unknown model " + requestedModelName));
        }
    }

    private Set<Object> aggregateModels(Class<? extends HierarchicalElement> modelType, CompositeBuildActionParameters actionParameters, BuildCancellationToken cancellationToken) {
        Set<Object> results = new LinkedHashSet<Object>();
        final CompositeParameters compositeParameters = actionParameters.getCompositeParameters();
        final List<GradleParticipantBuild> participantBuilds = compositeParameters.getBuilds();
        File gradleUserHomeDir = compositeParameters.getGradleUserHomeDir();
        File daemonBaseDir = compositeParameters.getDaemonBaseDir();
        Integer daemonMaxIdleTimeValue = compositeParameters.getDaemonMaxIdleTimeValue();
        TimeUnit daemonMaxIdleTimeUnits = compositeParameters.getDaemonMaxIdleTimeUnits();
        final Set<BuildEnvironment> buildEnvironments = fetchModels(participantBuilds, BuildEnvironment.class, cancellationToken, gradleUserHomeDir, daemonBaseDir, daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits, null);
        CompositeContext context = null;
        if (supportsCompositeSubstitution(buildEnvironments)) {
            final Set<GradleProject> gradleProjects = fetchModels(participantBuilds, GradleProject.class, cancellationToken, gradleUserHomeDir, daemonBaseDir, daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits, null);
            final Set<ProjectPublications> publications = fetchModels(participantBuilds, ProjectPublications.class, cancellationToken, gradleUserHomeDir, daemonBaseDir, daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits, null);
            context = buildContext(participantBuilds, gradleProjects, publications);
        }
        results.addAll(fetchModels(compositeParameters.getBuilds(), modelType, cancellationToken, gradleUserHomeDir, daemonBaseDir, daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits, context));
        return results;
    }

    private <T> Set<T> fetchModels(List<GradleParticipantBuild> participantBuilds, Class<T> modelType, final BuildCancellationToken cancellationToken, File gradleUserHomeDir, File daemonBaseDir, Integer daemonMaxIdleTimeValue, TimeUnit daemonMaxIdleTimeUnits, CompositeContext context) {
        final Set<T> results = new LinkedHashSet<T>();
        final File initScript = generateInitScriptFromContext(context);
        for (GradleParticipantBuild participant : participantBuilds) {
            if (cancellationToken.isCancellationRequested()) {
                break;
            }
            ProjectConnection projectConnection = connect(participant, gradleUserHomeDir, daemonBaseDir, daemonMaxIdleTimeValue, daemonMaxIdleTimeUnits);
            ModelBuilder<T> modelBuilder = projectConnection.model(modelType);
            if (initScript!=null) {
                modelBuilder.withArguments("-I", initScript.getAbsolutePath());
            }
            modelBuilder.withCancellationToken(new CancellationTokenAdapter(cancellationToken));
            if (cancellationToken.isCancellationRequested()) {
                projectConnection.close();
                break;
            }
            try {
                accumulate(results, modelBuilder.get());
            } catch (GradleConnectionException e) {
                throw new CompositeBuildExceptionVersion1(e);
            } finally {
                projectConnection.close();
            }
        }
        return results;
    }

    private <T> void accumulate(Set<T> allResults, Object element) {
        allResults.add(Cast.<T>uncheckedCast(element));
        if (element instanceof HierarchicalElement) {
            for (Object child : ((HierarchicalElement)element).getChildren().getAll()) {
                accumulate(allResults, child);
            }
        }
    }

    private ProjectConnection connect(GradleParticipantBuild build, File gradleUserHomeDir, File daemonBaseDir, Integer daemonMaxIdleTimeValue, TimeUnit daemonMaxIdleTimeUnits) {
        DefaultGradleConnector connector = getInternalConnector();
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
        return configureDistribution(connector, build).connect();
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

        @Override
        public boolean isCancellationRequested() {
            return token.isCancellationRequested();
        }

        @Override
        public BuildCancellationToken getToken() {
            return token;
        }
    }
}

