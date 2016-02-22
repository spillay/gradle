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

import org.gradle.integtests.tooling.fixture.CompositeToolingApiSpecification
import org.gradle.tooling.model.eclipse.EclipseProject

abstract class AbstractDemoCrossVersionSpec extends CompositeToolingApiSpecification {
    def participantA, participantB

    def setup() {
        useEmbeddedParticipants = true
    }

    String buildFileContent() {
        """
apply plugin: 'java'
apply plugin: 'maven'

group = 'org'
version = '1.0'

repositories {
    jcenter()
    maven {
        url("file://" + rootProject.file("../repo"))
    }
}

uploadArchives {
    repositories {
        mavenDeployer {
            repository(url: "file://" + rootProject.file("../repo"))
        }
    }
}
"""
    }

    Set<EclipseProject> getEclipseProjects() {
        return withCompositeConnection([participantA, participantB]) { connection ->
            connection.getModels(EclipseProject)
        }
    }

    EclipseProject findConsumer(Set<EclipseProject> eclipseProjects, File consumerProjectDir) {
        def consumerProject = eclipseProjects.find { it.gradleProject.projectDirectory == consumerProjectDir }
        assert consumerProject
        return consumerProject
    }

    def extractClasspath(EclipseProject consumerProject) {
        def classpath = consumerProject.classpath.collect {
            def module = it.gradleModuleVersion
            if (module) {
                module.group + ":" + module.name + ":" + module.version
            } else {
                it.file.absolutePath
            }
        }
        classpath
    }

    void assertContainsProjectReplacement(Set<EclipseProject> eclipseProjects, File consumerProjectDir, String producerPath, String replacedDependency) {
        def consumerProject = findConsumer(eclipseProjects, consumerProjectDir)

        def projectDependencies = consumerProject.projectDependencies.collect { it.path }
        def classpath = extractClasspath(consumerProject)

        assert !classpath.contains(replacedDependency) && projectDependencies.contains(producerPath)
    }

    void assertClasspathContains(Set<EclipseProject> eclipseProjects, File consumerProjectDir, String dependency) {
        def consumerProject = findConsumer(eclipseProjects, consumerProjectDir)
        def classpath = extractClasspath(consumerProject)

        assert classpath.contains(dependency)
    }
}

