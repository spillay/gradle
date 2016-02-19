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

package org.gradle.integtests.tooling.r212

class DemoCrossVersionSpec extends AbstractDemoCrossVersionSpec {
    def setup() {
        participantA = populate("A") {
            settingsFile << """
rootProject.name = "x"
"""
            buildFile << buildFileContent()
            buildFile << """
dependencies {
   compile "org:y:1.0"
}
"""
        }
        participantB = populate("B") {
            settingsFile << """
rootProject.name = "y"
"""
            buildFile << buildFileContent()
        }
    }

    def "replaces external dependencies with project dependency in composite"() {
        when:
        def eclipseProjects = getEclipseProjects()
        then:
        assertContainsProjectReplacement(eclipseProjects, participantA, "/B::", "org:y:1.0")
    }

    def "gets transitive dependencies from replaced project dependency in composite"() {
        given:
        participantB.buildFile << """
dependencies {
   compile "log4j:log4j:1.2.17"
}
"""
        when:
        def eclipseProjects = getEclipseProjects()
        then:
        assertContainsProjectReplacement(eclipseProjects, participantA, "/B::", "org:y:1.0")
        assertClasspathContains(getEclipseProjects(), participantA, "log4j:log4j:1.2.17")

    }
}
