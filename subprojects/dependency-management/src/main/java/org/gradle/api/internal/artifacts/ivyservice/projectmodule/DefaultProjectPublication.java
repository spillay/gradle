/*
 * Copyright 2014 the original author or authors.
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

import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;

import java.util.Set;

public class DefaultProjectPublication implements ProjectPublication {
    private final ModuleVersionIdentifier id;
    private final Set<ModuleVersionIdentifier> dependencies = Sets.newLinkedHashSet();
    private final Set<PublishArtifact> artifacts = Sets.newLinkedHashSet();

    public DefaultProjectPublication(ModuleVersionIdentifier id) {
        this.id = id;
    }

    public DefaultProjectPublication(ModuleVersionIdentifier id, Configuration conf) {
        this.id = id;
        // Should be properly mapping project dependencies to coordinates
        for (Configuration configuration : conf.getAll()) {
            // Should be using the supplied configuration
            if (configuration.getName().equals(Dependency.DEFAULT_CONFIGURATION)) {
                for (Dependency dependency : configuration.getAllDependencies()) {
                    dependencies.add(DefaultModuleVersionIdentifier.newId(dependency.getGroup(), dependency.getName(), dependency.getVersion()));
                }
            }
        }
        artifacts.addAll(conf.getAllArtifacts());
    }

    public ModuleVersionIdentifier getId() {
        return id;
    }

    @Override
    public Set<ModuleVersionIdentifier> getDependencies() {
        return dependencies;
    }

    @Override
    public Set<PublishArtifact> getArtifacts() {
        return artifacts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultProjectPublication that = (DefaultProjectPublication) o;
        return Objects.equal(id, that.id) &&
            Objects.equal(dependencies, that.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, dependencies);
    }
}
