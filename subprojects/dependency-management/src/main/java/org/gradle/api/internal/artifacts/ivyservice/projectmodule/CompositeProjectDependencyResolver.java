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

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.CompositeProjectComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultExcludeRuleConverter;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExternalModuleIvyDependencyDescriptorFactory;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.VersionSelectionReasons;
import org.gradle.api.internal.artifacts.publish.DefaultPublishArtifact;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.tasks.GradleBuild;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.component.local.model.DefaultCompositeProjectComponentIdentifier;
import org.gradle.internal.component.local.model.DefaultLocalComponentMetaData;
import org.gradle.internal.component.local.model.LocalComponentArtifactIdentifier;
import org.gradle.internal.component.local.model.LocalComponentMetaData;
import org.gradle.internal.component.model.*;
import org.gradle.internal.resolve.resolver.ArtifactResolver;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class CompositeProjectDependencyResolver implements ComponentMetaDataResolver, DependencyToComponentIdResolver, ArtifactResolver {
    private final Map<String, CompositeBuildContext.Publication> mappings = Maps.newHashMap();

    public CompositeProjectDependencyResolver(ServiceRegistry registry) {
        for (CompositeBuildContext context : registry.getAll(CompositeBuildContext.class)) {
            for (CompositeBuildContext.Publication publication : context.getPublications()) {
                mappings.put(publication.getModuleId().toString(), publication);
            }
        }
    }

    public void resolve(DependencyMetaData dependency, BuildableComponentIdResolveResult result) {
        if (dependency.getSelector() instanceof ModuleComponentSelector) {
            ModuleComponentSelector selector = (ModuleComponentSelector) dependency.getSelector();
            CompositeBuildContext.Publication compositeTarget = findCompositeTarget(selector);
            if (compositeTarget == null) {
                return;
            }

            LocalComponentMetaData metaData = buildProjectComponentMetadata(compositeTarget);
            result.resolved(metaData);
            result.setSelectionReason(VersionSelectionReasons.COMPOSITE_BUILD);
        }
    }

    private CompositeBuildContext.Publication findCompositeTarget(ModuleComponentSelector selector) {
        String candidate = selector.getGroup() + ":" + selector.getModule();
        return mappings.get(candidate);
    }

    private LocalComponentMetaData buildProjectComponentMetadata(CompositeBuildContext.Publication publication) {
        String projectPath = publication.getProjectPath();
        ModuleVersionIdentifier moduleVersionIdentifier = new DefaultModuleVersionIdentifier(publication.getModuleId(), "1");
        ComponentIdentifier componentIdentifier = new DefaultCompositeProjectComponentIdentifier(projectPath);
        DefaultLocalComponentMetaData metadata = new DefaultLocalComponentMetaData(moduleVersionIdentifier, componentIdentifier, "integration");

        final TaskDependency buildDependencies = createTaskDependency(publication);
        metadata.addConfiguration("compile", "", Collections.<String>emptySet(), Sets.newHashSet("compile"), true, true, buildDependencies);
        metadata.addConfiguration("default", "", Sets.newHashSet("compile"), Sets.newHashSet("compile", "default"), true, true, buildDependencies);

        for (ModuleVersionIdentifier dependency : publication.getDependencies()) {
            DefaultExternalModuleDependency externalModuleDependency = new DefaultExternalModuleDependency(dependency.getGroup(), dependency.getName(), dependency.getVersion());
            DependencyMetaData dependencyMetaData = new ExternalModuleIvyDependencyDescriptorFactory(new DefaultExcludeRuleConverter()).createDependencyDescriptor("compile", externalModuleDependency);
            metadata.addDependency(dependencyMetaData);
        }

        Set<PublishArtifact> artifacts = CollectionUtils.collect(publication.getArtifacts(), new Transformer<PublishArtifact, File>() {
            @Override
            public PublishArtifact transform(File file) {
                return new FilePublishArtifact(file, buildDependencies);
            }
        });
        metadata.addArtifacts("compile", artifacts);

        return metadata;
    }

    private TaskDependency createTaskDependency(final CompositeBuildContext.Publication publication) {
        final String taskName = "createExternalProject_" + publication.getModuleId().getGroup() + "_" + publication.getModuleId().getName();
        return new TaskDependency() {
            @Override
            public Set<? extends Task> getDependencies(Task task) {
                TaskContainer tasks = task.getProject().getTasks();
                Task depTask = tasks.findByName(taskName);
                if (depTask == null) {
                    depTask = tasks.create(taskName, GradleBuild.class, new Action<GradleBuild>() {
                        @Override
                        public void execute(GradleBuild gradleBuild) {
                            gradleBuild.setDir(publication.getProjectDirectory());
                            gradleBuild.setTasks(Collections.singleton(publication.getTaskName()));
                        }
                    });
                }
                return Collections.singleton(depTask);
            };
        };
    }

    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
//        if (identifier instanceof ProjectComponentIdentifier) {
//            String projectPath = ((ProjectComponentIdentifier) identifier).getProjectPath();
//            LocalComponentMetaData componentMetaData = projectComponentRegistry.getProject(projectPath);
//            if (componentMetaData == null) {
//                result.failed(new ModuleVersionResolveException(new DefaultProjectComponentSelector(projectPath), "project '" + projectPath + "' not found."));
//            } else {
//                result.resolved(componentMetaData);
//            }
//        }
    }

    public void resolveModuleArtifacts(ComponentResolveMetaData component, ArtifactType artifactType, BuildableArtifactSetResolveResult result) {
        if (isCompositeProjectId(component.getComponentId())) {
            throw new UnsupportedOperationException("Resolving artifacts by type is not yet supported for project modules");
        }
    }

    public void resolveModuleArtifacts(ComponentResolveMetaData component, ComponentUsage usage, BuildableArtifactSetResolveResult result) {
        if (isCompositeProjectId(component.getComponentId())) {
            String configurationName = usage.getConfigurationName();
            Set<ComponentArtifactMetaData> artifacts = component.getConfiguration(configurationName).getArtifacts();
            result.resolved(artifacts);
        }
    }

    public void resolveArtifact(ComponentArtifactMetaData component, ModuleSource moduleSource, BuildableArtifactResolveResult result) {
        if (isCompositeProjectId(component.getComponentId())) {
            LocalComponentArtifactIdentifier id = (LocalComponentArtifactIdentifier) component.getId();
            File localArtifactFile = id.getFile();
            if (localArtifactFile != null) {
                result.resolved(localArtifactFile);
            } else {
                result.notFound(component.getId());
            }
        }
    }

    private boolean isCompositeProjectId(ComponentIdentifier componentId) {
        return componentId instanceof CompositeProjectComponentIdentifier;
    }

    public static class FilePublishArtifact extends DefaultPublishArtifact {
        private final TaskDependency buildDeps;

        public FilePublishArtifact(File file, TaskDependency buildDependencies) {
            super(determineName(file), determineExtension(file), "jar", null, null, file);
            this.buildDeps = buildDependencies;
        }

        @Override
        public TaskDependency getBuildDependencies() {
            return buildDeps;
        }

        private static String determineExtension(File file) {
            return StringUtils.substringAfterLast(file.getName(), ".");
        }

        private static String determineName(File file) {
            return StringUtils.substringBeforeLast(file.getName(), ".");
        }
    }

}
