/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.composite.internal.plugins;

import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.plugin.management.internal.InvalidPluginRequestException;
import org.gradle.plugin.management.internal.PluginRequestInternal;
import org.gradle.plugin.use.PluginId;
import org.gradle.plugin.use.resolve.internal.PluginResolution;
import org.gradle.plugin.use.resolve.internal.PluginResolutionResult;
import org.gradle.plugin.use.resolve.internal.PluginResolver;
import org.gradle.plugin.use.resolve.internal.PluginResolverContributor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CompositeBuildPluginResolverContributor implements PluginResolverContributor {

    private static final String SOURCE_DESCRIPTION = "Included Builds";

    private final PluginResolver resolver;

    public CompositeBuildPluginResolverContributor(BuildStateRegistry buildRegistry, BuildState consumingBuild) {
        this.resolver = new CompositeBuildPluginResolver(buildRegistry, consumingBuild);
    }

    @Override
    public void collectResolversInto(Collection<PluginResolver> resolvers) {
        resolvers.add(resolver);
    }

    private static class CompositeBuildPluginResolver implements PluginResolver {

        private final BuildStateRegistry buildRegistry;
        private final BuildState consumingBuild;

        private final Map<PluginId, PluginResolution> results = new HashMap<>();

        private CompositeBuildPluginResolver(BuildStateRegistry buildRegistry, BuildState consumingBuild) {
            this.buildRegistry = buildRegistry;
            this.consumingBuild = consumingBuild;
        }

        @Override
        public void resolve(PluginRequestInternal pluginRequest, PluginResolutionResult result) throws InvalidPluginRequestException {
            if (buildRegistry.getIncludedBuilds().isEmpty()) {
                return;
            }

            PluginResolution resolution = results.computeIfAbsent(pluginRequest.getId(), this::resolvePluginFromIncludedBuilds);
            if (resolution != null) {
                result.found(SOURCE_DESCRIPTION, resolution);
            } else {
                result.notFound(SOURCE_DESCRIPTION, "None of the included builds contain this plugin");
            }
        }

        private PluginResolution resolvePluginFromIncludedBuilds(PluginId requestedPluginId) {
            for (IncludedBuildState build : buildRegistry.getIncludedBuilds()) {
                if (build == consumingBuild || build.isImplicitBuild() || build.isPluginBuild()) {
                    continue;
                }
                Optional<PluginResolution> pluginResolution = build.withState(gradleInternal -> LocalPluginResolution.resolvePlugin(gradleInternal, requestedPluginId));
                if (pluginResolution.isPresent()) {
                    // TODO: once we are ready to publicize build logic build inclusion via pluginManagement {}
                    /*DeprecationLogger.deprecateAction("Including builds that contribute Gradle plugins outside of pluginManagement {} block in settings file")
                        .willBeRemovedInGradle8()
                        .withUpgradeGuideSection(6, "included_builds_contributing_plugins")
                        .nagUser();*/
                    return pluginResolution.get();
                }
            }
            return null;
        }
    }
}
