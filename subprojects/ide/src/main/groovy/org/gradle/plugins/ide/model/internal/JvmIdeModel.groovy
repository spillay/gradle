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

import com.google.common.collect.ImmutableList

/**
 * IDE view of the JVM software model.
 */
class JvmIdeModel {
    final ImmutableList<JvmIdeSourceDir> sourceDirs

    // Must be kept Iterable<File> and resolve it on demand
    // because trying to resolve dependencies from inside a model rule
    // causes a stack overflow
    private final Iterable<File> testClasspath

    JvmIdeModel(Iterable<JvmIdeSourceDir> sourceDirs, Iterable<File> testClasspath) {
        this.sourceDirs = ImmutableList.copyOf(sourceDirs)
        this.testClasspath = testClasspath
    }

    public Set<File> getTestClasspath() {
        testClasspath.findAll { it.file } as LinkedHashSet
    }

    public List<JvmIdeSourceDir> sourceDirsByKind(JvmIdeSourceDirKind kind) {
        sourceDirs.findAll { it.kind == kind }.collect { it.dir }
    }

}
