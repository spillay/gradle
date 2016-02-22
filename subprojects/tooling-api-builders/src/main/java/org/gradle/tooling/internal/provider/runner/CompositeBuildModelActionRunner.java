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
import org.gradle.logging.ProgressLoggerFactory;
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
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.gradle.BasicGradleProject;
import org.gradle.tooling.model.gradle.GradleBuild;
import org.gradle.tooling.model.gradle.GradlePublication;
import org.gradle.tooling.model.gradle.ProjectPublications;
import org.gradle.util.CollectionUtils;
import org.gradle.util.GFileUtils;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CompositeBuildModelActionRunner implements CompositeBuildActionRunner {
    private Map<String, Class<? extends HierarchicalElement>> modelRequestTypeToModelTypeMapping = new HashMap<String, Class<? extends HierarchicalElement>>() {{
        this.put(SetOfEclipseProjects.class.getName(), EclipseProject.class);
    }};

    private static final class CompositeContext {
        private final Map<String, PublicationSetForParticipant> publications = Maps.newHashMap();
    }

    private File generateInitScriptFromContext(CompositeContext context) {
        if (context!=null) {
            // TODO: Hack, goes away when we start registering these directly in the build
            File initScript = new File("/tmp/init.gradle");
            StringBuilder sb = new StringBuilder();
            sb.append("def context = services.get(org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext)\n");
            for (Map.Entry<String, PublicationSetForParticipant> e : context.publications.entrySet()) {
                String participantName = e.getKey();
                PublicationSetForParticipant publicationSetForParticipant = e.getValue();
                sb.append("// Publications from participant ").append(participantName).append("\n");
                for (Map.Entry<String, ProjectPublications> publicationForProject : publicationSetForParticipant.projectsToPublication.entrySet()) {
                    String projectPath = publicationForProject.getKey();
                    ProjectPublications publications = publicationForProject.getValue();

                    sb.append("// Publications from project with path ").append(projectPath).append("\n");
                    for (GradlePublication publication : publications.getPublications().getAll()) {
                        String compositePath = participantName + ":" + projectPath;
                        Set<String> deps = CollectionUtils.collect(publication.getDependencies(), new Transformer<String, GradleModuleVersion>() {
                            public String transform(GradleModuleVersion id) {
                                return id.getGroup() + ":" + id.getName() + ":" + id.getVersion();
                            }
                        });
                        Set<String> artifacts = CollectionUtils.collect(publication.getArtifacts(), new Transformer<String, File>() {
                            @Override
                            public String transform(File file) {
                                return file.getAbsolutePath();
                            }
                        });
                        String registration = String.format("context.register('%s', '%s', [%s], [%s])\n", groupName(publication.getId()), compositePath, quoteAll(deps), quoteAll(artifacts));
                        sb.append(registration);
                        sb.append("// Produced by tasks ").append(publication.getTasks()).append("\n");
                    }
                }
            }
            GFileUtils.writeStringToFile(initScript, sb.toString());
            return initScript;
        }
        return null;
    }

    private String quoteAll(Set<String> deps) {
        return deps.isEmpty() ? "" : "'" + StringUtils.join(deps, "', '") + "'";
    }

    private String groupName(GradleModuleVersion id) {
        return id.getGroup() + ":" + id.getName();
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
            ProgressLoggerFactory progressLoggerFactory = buildController.getBuildScopeServices().get(ProgressLoggerFactory.class);
            Set<Object> results = aggregateModels(modelType, actionParameters, requestContext.getCancellationToken(), progressLoggerFactory);
            SetContainer setContainer = new SetContainer(results);
            PayloadSerializer payloadSerializer = buildController.getBuildScopeServices().get(PayloadSerializer.class);
            buildController.setResult(new BuildActionResult(payloadSerializer.serialize(setContainer), null));
        } else {
            throw new CompositeBuildExceptionVersion1(new IllegalArgumentException("Unknown model " + requestedModelName));
        }
    }

    private Set<Object> aggregateModels(Class<? extends HierarchicalElement> modelType, CompositeBuildActionParameters actionParameters, BuildCancellationToken cancellationToken, ProgressLoggerFactory progressLoggerFactory) {
        Set<Object> results = new LinkedHashSet<Object>();
        final CompositeParameters compositeParameters = actionParameters.getCompositeParameters();

        final List<GradleParticipantBuild> participantBuilds = compositeParameters.getBuilds();
        final Set<BuildEnvironment> buildEnvironments = fetchModels(participantBuilds, BuildEnvironment.class, cancellationToken, compositeParameters, progressLoggerFactory, null);
        CompositeContext context = null;
        if (supportsCompositeSubstitution(buildEnvironments)) {
            context = buildContext(participantBuilds, cancellationToken, compositeParameters);
        }
        results.addAll(fetchModels(compositeParameters.getBuilds(), modelType, cancellationToken, compositeParameters, progressLoggerFactory, context));
        return results;
    }

    private <T> Set<T> fetchModels(List<GradleParticipantBuild> participantBuilds, Class<T> modelType, final BuildCancellationToken cancellationToken, CompositeParameters compositeParameters, ProgressLoggerFactory progressLoggerFactory, CompositeContext context) {
        final Set<T> results = new LinkedHashSet<T>();
        final File initScript = generateInitScriptFromContext(context);
        for (GradleParticipantBuild participant : participantBuilds) {
            if (cancellationToken.isCancellationRequested()) {
                break;
            }
            ProjectConnection projectConnection = connect(participant, compositeParameters);
            ModelBuilder<T> modelBuilder = projectConnection.model(modelType);
            if (initScript!=null) {
                modelBuilder.withArguments("-I", initScript.getAbsolutePath());
            }

            modelBuilder.withCancellationToken(new CancellationTokenAdapter(cancellationToken));
            modelBuilder.addProgressListener(new ProgressListenerToProgressLoggerAdapter(progressLoggerFactory));
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

    private static final class PublicationSetForParticipant implements Serializable {
        Map<String, ProjectPublications> projectsToPublication; // : or :x -> publications
    }

    private CompositeContext buildContext(List<GradleParticipantBuild> participantBuilds, final BuildCancellationToken cancellationToken, CompositeParameters compositeParameters) {
        final CompositeContext context = new CompositeContext();
        for (GradleParticipantBuild participant : participantBuilds) {
            if (cancellationToken.isCancellationRequested()) {
                break;
            }
            // TODO: Need to figure out a way to determine participant name
            final String participantName = participant.getProjectDir().getName();
            ProjectConnection projectConnection = connect(participant, compositeParameters);
            try {
                PublicationSetForParticipant result = projectConnection.action(new RetrievePublicationAction()).
                    withCancellationToken(new CancellationTokenAdapter(cancellationToken)).
                    run();
                context.publications.put(participantName, result);
            } finally {
                projectConnection.close();
            }
        }
        return context;
    }

    private <T> void accumulate(Set<T> allResults, Object element) {
        allResults.add(Cast.<T>uncheckedCast(element));
        if (element instanceof HierarchicalElement) {
            for (Object child : ((HierarchicalElement)element).getChildren().getAll()) {
                accumulate(allResults, child);
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

    private final static class RetrievePublicationAction implements org.gradle.tooling.BuildAction<PublicationSetForParticipant> {
        @Override
        public PublicationSetForParticipant execute(BuildController controller) {
            GradleBuild buildModel = controller.getBuildModel();
            PublicationSetForParticipant publicationSetForParticipant = new PublicationSetForParticipant();
            publicationSetForParticipant.projectsToPublication = new HashMap<String, ProjectPublications>();
            accumulate(controller, buildModel.getRootProject(), publicationSetForParticipant);
            return publicationSetForParticipant;
        }

        private void accumulate(BuildController controller, BasicGradleProject project, PublicationSetForParticipant result) {
            ProjectPublications projectPublications = controller.getModel(project, ProjectPublications.class);
            result.projectsToPublication.put(project.getPath(), projectPublications);
            for (BasicGradleProject child : project.getChildren()) {
                accumulate(controller, child, result);
            }
        }
    }
}
