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

package org.gradle.tooling.internal.gradle;

import com.google.common.base.Objects;
import com.google.common.collect.Sets;
import org.gradle.tooling.model.GradleModuleVersion;

import java.io.File;
import java.io.Serializable;
import java.util.Set;

public class DefaultGradlePublication implements Serializable {
    private GradleModuleVersion id;
    private Set<GradleModuleVersion> dependencies = Sets.newLinkedHashSet();
    private Set<File> artifacts = Sets.newLinkedHashSet();
    private Set<String> tasks = Sets.newLinkedHashSet();

    public GradleModuleVersion getId() {
        return id;
    }

    public Set<GradleModuleVersion> getDependencies() {
        return dependencies;
    }

    public Set<File> getArtifacts() {
        return artifacts;
    }

    public Set<String> getTasks() {
        return tasks;
    }

    public void setId(GradleModuleVersion id) {
        this.id = id;
    }

    public void addDependency(GradleModuleVersion id) {
        dependencies.add(id);
    }

    public void addArtifact(File artifact) {
        artifacts.add(artifact);
    }

    public void addTask(String task) {
        tasks.add(task);
    }

    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .toString();
    }
}
