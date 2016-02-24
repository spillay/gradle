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
import org.gradle.BuildResult;
import org.gradle.StartParameter;
import org.gradle.api.Task;
import org.gradle.api.artifacts.*;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.specs.Spec;
import org.gradle.initialization.GradleLauncher;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.Collections;
import java.util.Set;

public class DefaultCompositeBuildController implements CompositeBuildController {
    private final CompositeBuildContext context;
    private final GradleLauncherFactory gradleLauncherFactory;
    private final StartParameter startParameter;

    public DefaultCompositeBuildController(ServiceRegistry registry) {
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
        return new ProjectMetaData() {
            @Override
            public ModuleIdentifier getModuleId() {
                return publication.getModuleId();
            }

            @Override
            public Set<ModuleVersionIdentifier> getDependencies() {
                return dependencies;
            }

            @Override
            public Set<File> getArtifacts() {
                return artifacts;
            }

            @Override
            public Set<String> getTaskNames() {
                return taskNames;
            }
        };
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

    @Override
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
}
