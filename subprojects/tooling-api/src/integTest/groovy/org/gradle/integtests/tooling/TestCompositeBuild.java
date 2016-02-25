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

package org.gradle.integtests.tooling;

import com.google.common.collect.Lists;
import org.gradle.StartParameter;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeContextBuilder;
import org.gradle.configuration.GradleLauncherMetaData;
import org.gradle.initialization.*;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.BuildSessionScopeServices;
import org.gradle.internal.service.scopes.GlobalScopeServices;
import org.gradle.logging.LoggingServiceRegistry;

import java.io.File;

public class TestCompositeBuild {
    public static void main(String[] args) {
        NativeServices.initialize(new File("/tmp/nativeServices"));
        LoggingServiceRegistry loggingServices = LoggingServiceRegistry.newCommandLineProcessLogging();
        DefaultServiceRegistry globalServices = (DefaultServiceRegistry) ServiceRegistryBuilder.builder()
                .displayName("Global services")
                .parent(loggingServices)
                .parent(NativeServices.getInstance())
                .provider(new GlobalScopeServices(false))
                .build();

        GradleLauncherFactory launcherFactory = globalServices.get(GradleLauncherFactory.class);

        StartParameter startParameter = new StartParameter();
        startParameter.setTaskNames(Lists.newArrayList("clean", "dependencies", "build"));
        startParameter.setSearchUpwards(false);

        DefaultServiceRegistry compositeServices = (DefaultServiceRegistry) ServiceRegistryBuilder.builder()
            .displayName("Composite services")
            .parent(globalServices)
            .build();
        compositeServices.add(CompositeBuildContext.class, buildCompositeContext(globalServices));
        compositeServices.addProvider(new CompositeScopeServices(startParameter, compositeServices));

        ServiceRegistry buildSessionServices = new BuildSessionScopeServices(compositeServices, startParameter, ClassPath.EMPTY);
        DefaultBuildRequestContext requestContext = new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(clientMetaData(), getBuildStartTime()), new DefaultBuildCancellationToken(), new NoOpBuildEventConsumer());

        try {
            launcherFactory.newInstance(startParameter, requestContext, buildSessionServices).run();
        } finally {
            globalServices.close();
        }
    }

    private static CompositeBuildContext buildCompositeContext(DefaultServiceRegistry globalServices) {
        CompositeContextBuilder builder = new CompositeContextBuilder(globalServices.get(GradleLauncherFactory.class));
        builder.addParticipant("A", new File("/Users/daz/dev/gradle/gradle/design-docs/features/composite-build/dependency-substitution/demo/projects/A"));
        builder.addParticipant("B", new File("/Users/daz/dev/gradle/gradle/design-docs/features/composite-build/dependency-substitution/demo/projects/B"));
        builder.addParticipant("C", new File("/Users/daz/dev/gradle/gradle/design-docs/features/composite-build/dependency-substitution/demo/projects/C"));
        return builder.build();
    }

    private static long getBuildStartTime() {
        return System.currentTimeMillis();
    }

    private static GradleLauncherMetaData clientMetaData() {
        return new GradleLauncherMetaData();
    }

}
