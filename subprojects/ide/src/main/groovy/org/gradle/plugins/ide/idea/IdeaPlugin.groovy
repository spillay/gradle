/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.plugins.ide.idea

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.plugins.scala.ScalaBasePlugin
import org.gradle.internal.reflect.Instantiator
import org.gradle.jvm.plugins.JvmComponentPlugin
import org.gradle.model.internal.registry.ModelRegistry
import org.gradle.plugins.ide.api.XmlFileContentMerger
import org.gradle.plugins.ide.idea.internal.IdeaNameDeduper
import org.gradle.plugins.ide.idea.internal.IdeaScalaConfigurer
import org.gradle.plugins.ide.idea.model.*
import org.gradle.plugins.ide.internal.IdePlugin
import org.gradle.plugins.ide.model.internal.JvmIdeModel
import org.gradle.plugins.ide.model.internal.JvmIdeModelRules
import org.gradle.plugins.ide.model.internal.JvmIdeSourceDirKind

import javax.inject.Inject

import static JvmIdeModelRules.ideModelFor
import static org.gradle.plugins.ide.model.internal.JvmIdeSourceDirKind.PRODUCTION
import static org.gradle.plugins.ide.model.internal.JvmIdeSourceDirKind.TEST

/**
 * Adds a GenerateIdeaModule task. When applied to a root project, also adds a GenerateIdeaProject task.
 * For projects that have the Java plugin applied, the tasks receive additional Java-specific configuration.
 */
class IdeaPlugin extends IdePlugin {
    private final Instantiator instantiator
    private final ModelRegistry modelRegistry
    private IdeaModel ideaModel

    @Inject
    IdeaPlugin(Instantiator instantiator, ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry
        this.instantiator = instantiator
    }

    public IdeaModel getModel() {
        ideaModel
    }

    @Override protected String getLifecycleTaskName() {
        return 'idea'
    }

    @Override protected void onApply(Project project) {
        lifecycleTask.description = 'Generates IDEA project files (IML, IPR, IWS)'
        cleanTask.description = 'Cleans IDEA project files (IML, IPR)'

        ideaModel = project.extensions.create("idea", IdeaModel)

        configureIdeaWorkspace(project)
        configureIdeaProject(project)
        configureIdeaModule(project)
        configureForJavaPlugin(project)
        configureForScalaPlugin()
        hookDeduplicationToTheRoot(project)
    }

    void hookDeduplicationToTheRoot(Project project) {
        if (isRoot(project)) {
            project.gradle.projectsEvaluated {
                makeSureModuleNamesAreUnique()
            }
        }
    }

    public void makeSureModuleNamesAreUnique() {
        new IdeaNameDeduper().configureRoot(project.rootProject)
    }

    private configureIdeaWorkspace(Project project) {
        if (isRoot(project)) {
            def task = project.task('ideaWorkspace', description: 'Generates an IDEA workspace file (IWS)', type: GenerateIdeaWorkspace) {
                workspace = new IdeaWorkspace(iws: new XmlFileContentMerger(xmlTransformer))
                ideaModel.workspace = workspace
                outputFile = new File(project.projectDir, project.name + ".iws")
            }
            addWorker(task, false)
        }
    }

    private configureIdeaProject(Project project) {
        if (isRoot(project)) {
            def task = project.task('ideaProject', description: 'Generates IDEA project file (IPR)', type: GenerateIdeaProject) {
                def ipr = new XmlFileContentMerger(xmlTransformer)
                ideaProject = instantiator.newInstance(IdeaProject, project, ipr)

                ideaModel.project = ideaProject

                ideaProject.outputFile = new File(project.projectDir, project.name + ".ipr")
                ideaProject.conventionMapping.jdkName = { JavaVersion.current().toString() }
                ideaProject.conventionMapping.languageLevel = {
                    JavaVersion maxSourceCompatibility = getMaxJavaModuleCompatibilityVersionFor { Project p ->
                        p.convention.getPlugin(JavaPluginConvention).sourceCompatibility
                    }
                    new IdeaLanguageLevel(maxSourceCompatibility)
                }

                ideaProject.wildcards = ['!?*.java', '!?*.groovy'] as Set
                ideaProject.conventionMapping.modules = {
                    project.rootProject.allprojects.findAll { it.plugins.hasPlugin(IdeaPlugin) }.collect { it.idea.module }
                }

                ideaProject.conventionMapping.pathFactory = {
                    new PathFactory().addPathVariable('PROJECT_DIR', outputFile.parentFile)
                }
            }
            addWorker(task)
        }
    }

    private JavaVersion getMaxJavaModuleCompatibilityVersionFor(Closure collectClosure) {
        List<JavaVersion> allProjectJavaVersions = project.rootProject.allprojects.findAll { it.plugins.hasPlugin(IdeaPlugin) && it.plugins.hasPlugin(JavaBasePlugin) }.collect(collectClosure)
        JavaVersion maxJavaVersion = allProjectJavaVersions.max() ?: JavaVersion.VERSION_1_6
        maxJavaVersion
    }

    private configureIdeaModule(Project project) {
        def task = project.task('ideaModule', description: 'Generates IDEA module files (IML)', type: GenerateIdeaModule) {
            def iml = new IdeaModuleIml(xmlTransformer, project.projectDir)
            module = instantiator.newInstance(IdeaModule, project, iml)

            ideaModel.module = module

            module.conventionMapping.name = { project.name }
            module.conventionMapping.contentRoot = { project.projectDir }
            module.conventionMapping.sourceDirs = { defaultSourceDirsFor(PRODUCTION) }
            module.conventionMapping.testSourceDirs = { defaultSourceDirsFor(TEST) }
            module.conventionMapping.excludeDirs = { [project.buildDir, project.file('.gradle')] as LinkedHashSet }
            module.conventionMapping.pathFactory = {
                PathFactory factory = new PathFactory()
                factory.addPathVariable('MODULE_DIR', outputFile.parentFile)
                module.pathVariables.each { key, value ->
                    factory.addPathVariable(key, value)
                }
                factory
            }
            if (!hasPlugin(JavaPlugin)) {
                module.conventionMapping.singleEntryLibraries = {
                    def classpath = defaultTestClasspath().toSet()
                    [
                        RUNTIME: [],
                        TEST: classpath
                    ]
                }
            }
        }

        addWorker(task)
    }

    private LinkedHashSet<File> defaultSourceDirsFor(JvmIdeSourceDirKind dirKind) {
        def ideModel = findIdeModel()
        if (ideModel != null) {
            ideModel.sourceDirsByKind(dirKind) as LinkedHashSet
        } else {
            [] as LinkedHashSet
        }
    }

    private JvmIdeModel findIdeModel() {
        if (!hasPlugin(JvmComponentPlugin))
            return null
        if (!hasPlugin(JvmIdeModelRules))
            project.plugins.apply(JvmIdeModelRules)
        return ideModelFor(modelRegistry)
    }

    private boolean hasPlugin(Class<?> plugin) {
        project.plugins.hasPlugin(plugin)
    }

    private configureForJavaPlugin(Project project) {
        project.plugins.withType(JavaPlugin) {
            configureIdeaModuleForJava(project)
        }
    }

    private configureIdeaModuleForJava(Project project) {
        project.ideaModule {
            module.conventionMapping.sourceDirs = { project.sourceSets.main.allSource.srcDirs as LinkedHashSet }
            module.conventionMapping.testSourceDirs = { project.sourceSets.test.allSource.srcDirs as LinkedHashSet }
            module.scopes = [
                    PROVIDED: [plus: [], minus: []],
                    COMPILE: [plus: [], minus: []],
                    RUNTIME: [plus: [], minus: []],
                    TEST: [plus: [], minus: []]
            ]
            module.conventionMapping.singleEntryLibraries = {
                [
                    RUNTIME: project.sourceSets.main.output.dirs,
                    TEST: project.sourceSets.test.output.dirs
                ]
            }
            dependsOn {
                project.sourceSets.main.output.dirs + project.sourceSets.test.output.dirs
            }
        }
    }

    private Iterable<File> defaultTestClasspath() {
        def ideModel = findIdeModel()
        if (ideModel != null) {
            ideModel.testClasspath
        } else {
            []
        }
    }

    private void configureForScalaPlugin() {
        project.plugins.withType(ScalaBasePlugin) {
            //see IdeaScalaConfigurer
            project.tasks.ideaModule.dependsOn(project.rootProject.tasks.ideaProject)
        }
        if (isRoot(project)) {
            new IdeaScalaConfigurer(project).configure()
        }
    }

    private boolean isRoot(Project project) {
        return project.parent == null
    }
}

