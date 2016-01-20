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

package org.gradle.plugins.ide.model.internal

import com.google.common.collect.Iterables
import org.gradle.jvm.internal.JarBinarySpecInternal
import org.gradle.jvm.test.internal.JvmTestSuiteBinarySpecInternal
import org.gradle.language.base.LanguageSourceSet
import org.gradle.model.Model
import org.gradle.model.ModelMap
import org.gradle.model.RuleSource
import org.gradle.model.internal.registry.ModelRegistry
import org.gradle.platform.base.ComponentSpec

import static org.gradle.plugins.ide.model.internal.JvmIdeSourceDirKind.PRODUCTION
import static org.gradle.plugins.ide.model.internal.JvmIdeSourceDirKind.TEST

/**
 * Assembles an {@link JvmIdeModel} out of components and testSuites.
 */
class JvmIdeModelRules extends RuleSource {

    static JvmIdeModel ideModelFor(ModelRegistry modelRegistry) {
        modelRegistry.find("jvmIdeModel", JvmIdeModel)
    }

    @Model
    JvmIdeModel jvmIdeModel(ModelMap<JarBinarySpecInternal> binaries, ModelMap<JvmTestSuiteBinarySpecInternal> testSuites) {
        def sourceDirs = productionSourcesFor(binaries) + testSourcesFor(testSuites)
        def testClasspath = classpathFor(testSuites)
        new JvmIdeModel(sourceDirs, testClasspath)
    }

    private Iterable<File> classpathFor(ModelMap<JvmTestSuiteBinarySpecInternal> binaries) {
        //TODO:LPTR change the line below to `if (true) {` to see the cycle and subsequent stack overflow
        if (false) {
            return binaries.collectMany { it.runtimeClasspath.files } as LinkedHashSet
        }
        // Just collect the classpaths but leave resolution for later
        return Iterables.concat(
            binaries.collect { binary -> binary.runtimeClasspath }
        )
    }

    private List<JvmIdeSourceDir> productionSourcesFor(ModelMap<JarBinarySpecInternal> binaries) {
        binaries.collectMany { binary ->
            sourceDirsFor(binary.library, PRODUCTION)
        }
    }

    private List<JvmIdeSourceDir> testSourcesFor(ModelMap<JvmTestSuiteBinarySpecInternal> binaries) {
        binaries.collectMany { binary ->
            sourceDirsFor(binary.testSuite, TEST)
        }
    }

    private List<JvmIdeSourceDir> sourceDirsFor(ComponentSpec component, JvmIdeSourceDirKind dirKind) {
        component.sources.collectMany { sourceSet ->
            sourceDirsFor(sourceSet, dirKind)
        }
    }

    private List<JvmIdeSourceDir> sourceDirsFor(LanguageSourceSet sourceSet, JvmIdeSourceDirKind dirKind) {
        sourceSet.source.srcDirs.collect { dir ->
            new JvmIdeSourceDir(dirKind, dir)
        }
    }
}
