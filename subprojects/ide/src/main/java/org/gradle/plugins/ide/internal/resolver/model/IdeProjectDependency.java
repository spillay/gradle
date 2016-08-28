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

package org.gradle.plugins.ide.internal.resolver.model;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;

public class IdeProjectDependency extends IdeDependency {
    private final ProjectComponentIdentifier projectId;
    private final String projectName;

    public IdeProjectDependency(ProjectComponentIdentifier projectId, String projectName) {
        this.projectId = projectId;
        this.projectName = projectName;
    }

    public IdeProjectDependency(ProjectComponentIdentifier projectId) {
        this.projectId = projectId;
        this.projectName = determineNameFromPath(projectId.getProjectPath());
    }

    public ProjectComponentIdentifier getProjectId() {
        return projectId;
    }

    public String getProjectPath() {
        return projectId.getProjectPath();
    }

    public String getProjectName() {
        return projectName;
    }

    private static String determineNameFromPath(String projectPath) {
        // This is less than ideal (currently only used for composite build dependencies)
        // TODO:DAZ Introduce a properly typed ComponentIdentifier for project components in a composite
        if (projectPath.endsWith("::")) {
            return projectPath.substring(0, projectPath.length() - 2);
        }
        int index = projectPath.lastIndexOf(':');
        return projectPath.substring(index + 1);
    }
}
