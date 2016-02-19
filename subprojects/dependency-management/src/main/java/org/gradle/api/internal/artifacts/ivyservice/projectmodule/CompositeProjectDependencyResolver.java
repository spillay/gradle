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
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.CompositeProjectComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DefaultExcludeRuleConverter;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.ExternalModuleIvyDependencyDescriptorFactory;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.VersionSelectionReasons;
import org.gradle.api.internal.artifacts.publish.DefaultPublishArtifact;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.internal.tasks.DefaultTaskDependency;
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

import java.io.File;
import java.util.*;

public class CompositeProjectDependencyResolver implements ComponentMetaDataResolver, DependencyToComponentIdResolver, ArtifactResolver, CompositeBuildContext {
    private final Map<String, RegisteredProjectPublication> mappings = Maps.newHashMap();

    public CompositeProjectDependencyResolver() {
    }

    @Override
    public void register(String module, String projectPath, List<String> dependencies, List<String> artifacts) {
        mappings.put(module, new RegisteredProjectPublication(module, projectPath, dependencies, artifacts));
    }

    public void resolve(DependencyMetaData dependency, BuildableComponentIdResolveResult result) {
        if (dependency.getSelector() instanceof ModuleComponentSelector) {
            ModuleComponentSelector selector = (ModuleComponentSelector) dependency.getSelector();
            RegisteredProjectPublication compositeTarget = findCompositeTarget(selector);
            if (compositeTarget == null) {
                return;
            }

            LocalComponentMetaData metaData = buildProjectComponentMetadata(compositeTarget, collectTargetConfigurations(dependency));
            result.resolved(metaData);
            result.setSelectionReason(VersionSelectionReasons.COMPOSITE_BUILD);
        }
    }

    private Set<String> collectTargetConfigurations(DependencyMetaData dependency) {
        Set<String> configurations = Sets.newHashSet();
        for (String moduleConfiguration : dependency.getModuleConfigurations()) {
            Collections.addAll(configurations, dependency.getDependencyConfigurations(moduleConfiguration, ""));
        }
        return configurations;
    }

    private RegisteredProjectPublication findCompositeTarget(ModuleComponentSelector selector) {
        String candidate = selector.getGroup() + ":" + selector.getModule();
        return mappings.get(candidate);
    }

    private LocalComponentMetaData buildProjectComponentMetadata(RegisteredProjectPublication publication, Set<String> configurations) {
        String projectPath = publication.projectPath;
        ModuleVersionIdentifier moduleVersionIdentifier = new DefaultModuleVersionIdentifier(publication.moduleId, "1");
        ComponentIdentifier componentIdentifier = new DefaultCompositeProjectComponentIdentifier(projectPath);
        DefaultLocalComponentMetaData metadata = new DefaultLocalComponentMetaData(moduleVersionIdentifier, componentIdentifier, "integration");

        metadata.addConfiguration("compile", "", Collections.<String>emptySet(), Sets.newHashSet("compile"), true, true, new DefaultTaskDependency());
        metadata.addConfiguration("default", "", Sets.newHashSet("compile"), Sets.newHashSet("compile", "default"), true, true, new DefaultTaskDependency());

        for (ModuleVersionIdentifier dependency : publication.dependencies) {
            DefaultExternalModuleDependency externalModuleDependency = new DefaultExternalModuleDependency(dependency.getGroup(), dependency.getName(), dependency.getVersion());
            DependencyMetaData dependencyMetaData = new ExternalModuleIvyDependencyDescriptorFactory(new DefaultExcludeRuleConverter()).createDependencyDescriptor("compile", externalModuleDependency);
            metadata.addDependency(dependencyMetaData);
        }

        metadata.addArtifacts("default", publication.artifacts);

        return metadata;
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

    private static class RegisteredProjectPublication {
        ModuleIdentifier moduleId;
        String projectPath;
        Set<ModuleVersionIdentifier> dependencies = Sets.newLinkedHashSet();
        Set<PublishArtifact> artifacts = Sets.newLinkedHashSet();

        public RegisteredProjectPublication(String module, String projectPath, Collection<String> dependencies, Collection<String> artifacts) {
            String[] ga = module.split(":");
            this.moduleId = DefaultModuleIdentifier.newId(ga[0], ga[1]);
            this.projectPath = projectPath;
            for (String dependency : dependencies) {
                String[] gav = dependency.split(":");
                this.dependencies.add(DefaultModuleVersionIdentifier.newId(gav[0], gav[1], gav[2]));
            }
            for (String artifact : artifacts) {
                File artifactFile = new File(artifact);
                PublishArtifact publishArtifact = new FilePublishArtifact(artifactFile);
                this.artifacts.add(publishArtifact);
            }
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

}
