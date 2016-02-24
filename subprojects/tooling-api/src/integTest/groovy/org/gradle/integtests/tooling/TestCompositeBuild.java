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
import com.google.common.collect.Sets;
import org.gradle.StartParameter;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.DefaultCompositeBuildContext;
import org.gradle.configuration.GradleLauncherMetaData;
import org.gradle.initialization.*;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.GlobalScopeServices;
import org.gradle.launcher.cli.ExecuteBuildAction;
import org.gradle.launcher.daemon.configuration.DaemonParameters;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.BuildExecuter;
import org.gradle.launcher.exec.DefaultBuildActionParameters;
import org.gradle.logging.LoggingServiceRegistry;

import java.io.File;
import java.util.Collections;
import java.util.Set;

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
        globalServices.add(CompositeBuildContext.class, configureComposite());
        BuildActionExecuter<BuildActionParameters> executer = globalServices.get(BuildExecuter.class);

        StartParameter startParameter = new StartParameter();
        startParameter.setTaskNames(Lists.newArrayList("clean", "dependencies", "build"));
        startParameter.setSearchUpwards(false);

        DaemonParameters daemonParameters = new DaemonParameters(new BuildLayoutParameters());
        daemonParameters.setEnabled(false);

        try {
            runBuild(startParameter, daemonParameters, executer, globalServices);
        } finally {
            globalServices.close();
        }
    }

    private static DefaultCompositeBuildContext configureComposite() {
        DefaultCompositeBuildContext defaultCompositeBuildContext = new DefaultCompositeBuildContext();
        String projectDir = "/Users/daz/dev/gradle/gradle/design-docs/features/composite-build/dependency-substitution/demo/projects/B/y";
        String jarPath = "/Users/daz/dev/gradle/gradle/design-docs/features/composite-build/dependency-substitution/demo/projects/B/y/build/libs/y-1.0.jar";
        Set<String> dependencies = Sets.newHashSet("log4j:log4j:1.2.4", "junit:junit:4.0");
        defaultCompositeBuildContext.register("org:y", "B::y", dependencies, Collections.singleton(jarPath), projectDir, "build");
        return defaultCompositeBuildContext;
    }

    private static Object runBuild(StartParameter startParameter, DaemonParameters daemonParameters, BuildActionExecuter<BuildActionParameters> executer, ServiceRegistry sharedServices) {
        BuildActionParameters parameters = new DefaultBuildActionParameters(
                daemonParameters.getEffectiveSystemProperties(),
                System.getenv(),
                SystemProperties.getInstance().getCurrentDir(),
                startParameter.getLogLevel(),
                daemonParameters.getDaemonUsage(), startParameter.isContinuous(), daemonParameters.isInteractive(), ClassPath.EMPTY);
        return executer.execute(
                new ExecuteBuildAction(startParameter),
                new DefaultBuildRequestContext(new DefaultBuildRequestMetaData(clientMetaData(), getBuildStartTime()), new DefaultBuildCancellationToken(), new NoOpBuildEventConsumer()),
                parameters,
                sharedServices);
    }

    private static long getBuildStartTime() {
        return System.currentTimeMillis();
    }

    private static GradleLauncherMetaData clientMetaData() {
        return new GradleLauncherMetaData();
    }

}
