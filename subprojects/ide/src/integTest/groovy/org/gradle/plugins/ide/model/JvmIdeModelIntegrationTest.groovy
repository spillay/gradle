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

package org.gradle.plugins.ide.model

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.plugins.ide.model.internal.JvmIdeModelRules

class JvmIdeModelIntegrationTest extends AbstractIntegrationSpec {

    def "Can build IDE model from JVM software model"() {
        given:
        buildFile << """
            plugins {
                id 'jvm-component'
                id 'java-lang'
                id 'junit-test-suite'
            }

            apply type: $JvmIdeModelRules.name

            model {
                components {
                    myLib(JvmLibrarySpec) {
                    }
                    myUtils(JvmLibrarySpec) {
                    }
                }

                testSuites {
                    myTestSuite(JUnitTestSuiteSpec) {
                        jUnitVersion '4.12'
                    }
                }

                tasks { ts ->
                    def ideModel = \$.jvmIdeModel
                    ts.create('reportIdeModel') {
                        doFirst {
                            ideModel.sourceDirs.each {
                                println "\$it.kind => \$it.dir"
                            }
                            ideModel.testClasspath.each {
                                println "TEST CLASSPATH => \$it.name"
                            }
                        }
                    }
                }
            }

            repositories {
                jcenter()
            }
        """

        when:
        succeeds('reportIdeModel')

        then:
        outputContains("PRODUCTION => ${file('src/myLib/java')}")
        outputContains("PRODUCTION => ${file('src/myUtils/java')}")
        outputContains("TEST => ${file('src/myTestSuite/java')}")
        outputContains("TEST CLASSPATH => junit-4.12.jar")
    }
}
