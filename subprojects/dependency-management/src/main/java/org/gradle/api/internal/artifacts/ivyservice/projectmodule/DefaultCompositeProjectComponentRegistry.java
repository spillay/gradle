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

package org.gradle.api.internal.artifacts.ivyservice.projectmodule;

import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultExcludeRuleConverter;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExternalModuleIvyDependencyDescriptorFactory;
import org.gradle.api.internal.artifacts.publish.DefaultPublishArtifact;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.initialization.GradleLauncher;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.component.local.model.DefaultCompositeProjectComponentIdentifier;
import org.gradle.internal.component.local.model.DefaultLocalComponentMetaData;
import org.gradle.internal.component.local.model.LocalComponentMetaData;
import org.gradle.internal.component.model.DependencyMetaData;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.Collections;
import java.util.Set;

public class DefaultCompositeProjectComponentRegistry implements CompositeProjectComponentRegistry {
    private final CompositeBuildContext context;
    private final GradleLauncherFactory gradleLauncherFactory;
    private final StartParameter startParameter;

    public DefaultCompositeProjectComponentRegistry(ServiceRegistry registry) {
        this.context = CollectionUtils.findSingle(registry.getAll(CompositeBuildContext.class));
        this.gradleLauncherFactory = registry.get(GradleLauncherFactory.class);
        this.startParameter = registry.get(StartParameter.class);
    }

    @Override
    public String getReplacementProject(ModuleComponentSelector selector) {
        String candidate = selector.getGroup() + ":" + selector.getModule();
        for (CompositeBuildContext.Publication publication : context.getPublications()) {
            if (publication.getModuleId().toString().equals(candidate)) {
                return publication.getProjectPath();
            }
        }
        return null;
    }

    @Override
    public LocalComponentMetaData getComponentMetadata(String projectPath) {
        ProjectMetaData projectMetaData = getMetaData(projectPath);
        return buildProjectComponentMetadata(projectPath, projectMetaData);
    }

    private LocalComponentMetaData buildProjectComponentMetadata(String projectPath, ProjectMetaData publication) {
        ModuleVersionIdentifier moduleVersionIdentifier = new DefaultModuleVersionIdentifier(publication.getModuleId(), "1");
        ComponentIdentifier componentIdentifier = new DefaultCompositeProjectComponentIdentifier(projectPath);
        DefaultLocalComponentMetaData metadata = new DefaultLocalComponentMetaData(moduleVersionIdentifier, componentIdentifier, "integration");

        Set<PublishArtifact> artifacts = CollectionUtils.collect(publication.getArtifacts(), new Transformer<PublishArtifact, File>() {
            @Override
            public PublishArtifact transform(File file) {
                return new FilePublishArtifact(file);
            }
        });
        TaskDependency buildDependencies = createTaskDependency(projectPath, publication.getModuleId(), publication.getTaskNames());

        metadata.addConfiguration("compile", "", Collections.<String>emptySet(), Sets.newHashSet("compile"), true, true, buildDependencies);
        metadata.addConfiguration("default", "", Sets.newHashSet("compile"), Sets.newHashSet("compile", "default"), true, true, buildDependencies);

        for (ModuleVersionIdentifier dependency : publication.getDependencies()) {
            DefaultExternalModuleDependency externalModuleDependency = new DefaultExternalModuleDependency(dependency.getGroup(), dependency.getName(), dependency.getVersion());
            DependencyMetaData dependencyMetaData = new ExternalModuleIvyDependencyDescriptorFactory(new DefaultExcludeRuleConverter()).createDependencyDescriptor("compile", externalModuleDependency);
            metadata.addDependency(dependencyMetaData);
        }

        metadata.addArtifacts("compile", artifacts);

        return metadata;
    }

    private TaskDependency createTaskDependency(final String projectPath, ModuleIdentifier moduleId, final Set<String> taskNames) {
        final String taskName = "createExternalProject_" + moduleId.getGroup() + "_" + moduleId.getName();
        return new TaskDependency() {
            @Override
            public Set<? extends Task> getDependencies(Task task) {
                TaskContainer tasks = task.getProject().getRootProject().getTasks();
                Task depTask = tasks.findByName(taskName);
                if (depTask == null) {
                    depTask = tasks.create(taskName, CompositeProjectBuild.class, new Action<CompositeProjectBuild>() {
                        @Override
                        public void execute(CompositeProjectBuild buildTask) {
                            buildTask.conf(projectPath, taskNames, DefaultCompositeProjectComponentRegistry.this);
                        }
                    });
                }
                return Collections.singleton(depTask);
            }
        };
    }

    public ProjectMetaData getMetaData(final String projectPath) {
        final Set<ModuleVersionIdentifier> dependencies;
        final Set<File> artifacts;
        final Set<String> taskNames;
        GradleLauncher launcher = getGradleLauncher(projectPath, Collections.<String>emptySet());
        try {
            BuildResult buildAnalysis = launcher.getBuildAnalysis();
            GradleInternal gradle = (GradleInternal) buildAnalysis.getGradle();
            ProjectInternal defaultProject = gradle.getDefaultProject();
            artifacts = determineArtifacts(defaultProject, Dependency.ARCHIVES_CONFIGURATION);
            dependencies = determineDependencies(defaultProject, Dependency.DEFAULT_CONFIGURATION);
            taskNames = determineTaskNames(defaultProject, Dependency.ARCHIVES_CONFIGURATION);
        } finally {
            launcher.stop();
        }

        final CompositeBuildContext.Publication publication = getPublication(projectPath);
        return new ProjectMetaData(publication, dependencies, artifacts, taskNames);
    }

    private Set<ModuleVersionIdentifier> determineDependencies(ProjectInternal defaultProject, String configurationName) {
        Set<ModuleVersionIdentifier> dependencies = Sets.newLinkedHashSet();
        Configuration defaultConfig = defaultProject.getConfigurations().getByName(configurationName);
        for (Dependency dependency : defaultConfig.getAllDependencies()) {
            dependencies.add(DefaultModuleVersionIdentifier.newId(dependency.getGroup(), dependency.getName(), dependency.getVersion()));
        }
        return dependencies;
    }

    private Set<File> determineArtifacts(ProjectInternal defaultProject, String configurationName) {
        Configuration archives = defaultProject.getConfigurations().getByName(configurationName);
        Set<File> artifacts = Sets.newLinkedHashSet();
        for (PublishArtifact publishArtifact : archives.getAllArtifacts()) {
            artifacts.add(publishArtifact.getFile());
        }
        return artifacts;
    }

    private Set<String> determineTaskNames(ProjectInternal defaultProject, String configurationName) {
        Configuration archives = defaultProject.getConfigurations().getByName(configurationName);
        Set<String> taskNames = Sets.newLinkedHashSet();
        for (PublishArtifact publishArtifact : archives.getAllArtifacts()) {
            Set<? extends Task> dependencies = publishArtifact.getBuildDependencies().getDependencies(null);
            for (Task dependency : dependencies) {
                taskNames.add(dependency.getName());
            }
        }
        return taskNames;
    }

    public void build(String projectPath, Set<String> taskNames) {
        GradleLauncher launcher = getGradleLauncher(projectPath, taskNames);
        try {
            launcher.run();
        } finally {
            launcher.stop();
        }
    }

    private GradleLauncher getGradleLauncher(String projectPath, Set<String> taskNames) {
        final CompositeBuildContext.Publication publication = getPublication(projectPath);

        StartParameter param = startParameter.newBuild();
        param.setProjectDir(publication.getProjectDirectory());
        param.setTaskNames(taskNames);

        return gradleLauncherFactory.newInstance(param);
    }

    private CompositeBuildContext.Publication getPublication(final String projectPath) {
        return CollectionUtils.findFirst(context.getPublications(), new Spec<CompositeBuildContext.Publication>() {
            @Override
            public boolean isSatisfiedBy(CompositeBuildContext.Publication element) {
                return element.getProjectPath().equals(projectPath);
            }
        });
    }

    public static class CompositeProjectBuild extends DefaultTask {
        private DefaultCompositeProjectComponentRegistry controller;
        private String projectPath;
        private Set<String> taskNames;

        public void conf(String path, Set<String> taskNames, DefaultCompositeProjectComponentRegistry controller) {
            projectPath = path;
            this.taskNames = taskNames;
            this.controller = controller;
        }

        @TaskAction
        public void build() {
            controller.build(projectPath, taskNames);
        }
    }

    public static class FilePublishArtifact extends DefaultPublishArtifact {
        public FilePublishArtifact(File file) {
            super(determineName(file), determineExtension(file), "jar", null, null, file);
        }

        private static String determineExtension(File file) {
            return StringUtils.substringAfterLast(file.getName(), ".");
        }

        private static String determineName(File file) {
            return StringUtils.substringBeforeLast(file.getName(), ".");
        }
    }

    private static class ProjectMetaData {
        private final CompositeBuildContext.Publication publication;
        private final Set<ModuleVersionIdentifier> dependencies;
        private final Set<File> artifacts;
        private final Set<String> taskNames;

        public ProjectMetaData(CompositeBuildContext.Publication publication, Set<ModuleVersionIdentifier> dependencies, Set<File> artifacts, Set<String> taskNames) {
            this.publication = publication;
            this.dependencies = dependencies;
            this.artifacts = artifacts;
            this.taskNames = taskNames;
        }

        public ModuleIdentifier getModuleId() {
            return publication.getModuleId();
        }

        public Set<ModuleVersionIdentifier> getDependencies() {
            return dependencies;
        }

        public Set<File> getArtifacts() {
            return artifacts;
        }

        public Set<String> getTaskNames() {
            return taskNames;
        }
    }
}
