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
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.internal.artifacts.publish.DefaultPublishArtifact;

import java.io.File;
import java.util.Collection;
import java.util.Set;

public class DefaultCompositeBuildContext implements CompositeBuildContext {
    private final Set<Publication> publications = Sets.newLinkedHashSet();

    @Override
    public Set<Publication> getPublications() {
        return publications;
    }

    @Override
    public void register(String module, String projectPath, Set<String> dependencies, Set<String> artifacts) {
        publications.add(new RegisteredProjectPublication(module, projectPath, dependencies, artifacts));
    }

    private static class RegisteredProjectPublication implements Publication {
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

        @Override
        public ModuleIdentifier getModuleId() {
            return moduleId;
        }

        @Override
        public String getProjectPath() {
            return projectPath;
        }

        @Override
        public Set<ModuleVersionIdentifier> getDependencies() {
            return dependencies;
        }

        @Override
        public Set<PublishArtifact> getArtifacts() {
            return artifacts;
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
