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

public class DemoMultiprojectCrossVersionSpec extends AbstractDemoCrossVersionSpec {
    def setup() {
        participantA = populate("A") {
            settingsFile << """
            rootProject.name = "x"
            """
            buildFile << buildFileContent()
            buildFile << """
            dependencies {
                compile "org:z:1.0"
                compile "org:y:1.0"
            }
            """
        }
        participantB = populate("B") {
            settingsFile << """
            rootProject.name = "B-root"
            include "y", "z"
"""

            buildFile << """
            subprojects {
                ${buildFileContent()}
            }
"""
        }
    }

    def "can replace dependencies from a multi-project producer"() {
        when:
        def eclipseProjects = getEclipseProjects()
        then:
        assertContainsProjectReplacement(eclipseProjects, participantA, "/B::y", "org:y:1.0")
        assertContainsProjectReplacement(eclipseProjects, participantA, "/B::z", "org:z:1.0")
    }

    def "can replace dependencies with transitive from a multi-project producer"() {
        given:
        participantB.buildFile << """
project(":y") {
   dependencies {
      compile "log4j:log4j:1.2.17"
   }
}
project(":z") {
   dependencies {
      compile "com.google.guava:guava:17.0"
   }
}
"""
        when:
        def eclipseProjects = getEclipseProjects()
        then:
        assertContainsProjectReplacement(eclipseProjects, participantA, "/B::y", "org:y:1.0")
        assertContainsProjectReplacement(eclipseProjects, participantA, "/B::z", "org:z:1.0")
        assertClasspathContains(eclipseProjects, participantA, "log4j:log4j:1.2.17")
        assertClasspathContains(eclipseProjects, participantA, "com.google.guava:guava:17.0")
    }
}
