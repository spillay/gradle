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
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.VersionSelectionReasons;
import org.gradle.api.internal.component.ArtifactType;
import org.gradle.api.internal.tasks.DefaultTaskDependency;
import org.gradle.internal.component.local.model.DefaultLocalComponentMetaData;
import org.gradle.internal.component.local.model.DefaultProjectComponentIdentifier;
import org.gradle.internal.component.local.model.LocalComponentMetaData;
import org.gradle.internal.component.model.*;
import org.gradle.internal.resolve.resolver.ArtifactResolver;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult;
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class CompositeProjectDependencyResolver implements ComponentMetaDataResolver, DependencyToComponentIdResolver, ArtifactResolver, CompositeBuildContext {
    private final Map<String, String> mappings = Maps.newHashMap();

    public CompositeProjectDependencyResolver() {
    }

    @Override
    public void register(String module, String projectPath) {
        mappings.put(module, projectPath);
    }

    public void resolve(DependencyMetaData dependency, BuildableComponentIdResolveResult result) {
        if (dependency.getSelector() instanceof ModuleComponentSelector) {
            ModuleComponentSelector selector = (ModuleComponentSelector) dependency.getSelector();
            String compositeTarget = findCompositeTarget(selector);
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

    private String findCompositeTarget(ModuleComponentSelector selector) {
        String candidate = selector.getGroup() + ":" + selector.getModule();
        return mappings.get(candidate);
    }

    private LocalComponentMetaData buildProjectComponentMetadata(String projectPath, Set<String> configurations) {
        ModuleVersionIdentifier moduleVersionIdentifier = DefaultModuleVersionIdentifier.newId("org", projectPath, "??");
        ComponentIdentifier componentIdentifier = new DefaultProjectComponentIdentifier(projectPath);
        DefaultLocalComponentMetaData metadata = new DefaultLocalComponentMetaData(moduleVersionIdentifier, componentIdentifier, "integration");
        for (String moduleConfiguration : configurations) {
            metadata.addConfiguration(moduleConfiguration, "", Collections.<String>emptySet(), Collections.<String>emptySet(), true, true, new DefaultTaskDependency());
        }
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
        if (isProjectModule(component.getComponentId())) {
            throw new UnsupportedOperationException("Resolving artifacts by type is not yet supported for project modules");
        }
    }

    public void resolveModuleArtifacts(ComponentResolveMetaData component, ComponentUsage usage, BuildableArtifactSetResolveResult result) {
        if (isProjectModule(component.getComponentId())) {
            throw new UnsupportedOperationException();
//            String configurationName = usage.getConfigurationName();
//            Set<ComponentArtifactMetaData> artifacts = component.getConfiguration(configurationName).getArtifacts();
//            result.resolved(artifacts);
        }
    }

    public void resolveArtifact(ComponentArtifactMetaData component, ModuleSource moduleSource, BuildableArtifactResolveResult result) {
        if (isProjectModule(component.getComponentId())) {
            throw new UnsupportedOperationException();
//            LocalComponentArtifactIdentifier id = (LocalComponentArtifactIdentifier) component.getId();
//            File localArtifactFile = id.getFile();
//            if (localArtifactFile != null) {
//                result.resolved(localArtifactFile);
//            } else {
//                result.notFound(component.getId());
//            }
        }
    }

    private boolean isProjectModule(ComponentIdentifier componentId) {
        return componentId instanceof ProjectComponentIdentifier;
    }
}
