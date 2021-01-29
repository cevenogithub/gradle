/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.integtests.tooling.fixture;

import org.gradle.integtests.fixtures.compatibility.CoverageContext;
import org.gradle.integtests.fixtures.executer.IntegrationTestBuildContext;
import org.gradle.integtests.fixtures.versions.ReleasedVersionDistributions;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.SystemProperties;
import org.gradle.testfixtures.internal.NativeServicesTestFixture;
import org.gradle.util.GradleVersion;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryListener;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine;
import org.spockframework.runtime.RunContext;
import org.spockframework.runtime.SpockEngine;
import org.spockframework.runtime.SpockEngineDescriptor;
import org.spockframework.runtime.SpockExecutionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.gradle.integtests.fixtures.compatibility.AbstractContextualMultiVersionTestInterceptor.VERSIONS_SYSPROP_NAME;


public class CrossVersionTestEngine extends HierarchicalTestEngine<SpockExecutionContext> {

    private final TestEngine delegateEngine = new SpockEngine();
    private final List<TestVariant> variants = new ArrayList<TestVariant>();

    @Override
    public String getId() {
        return "cross-version-test-engine";
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest discoveryRequest, UniqueId uniqueId) {
        String crossVersionTest = System.getProperty("org.gradle.integtest.crossVersion");
        if (crossVersionTest == null) {
            return new EngineDescriptor(uniqueId, "skip");
        }

        buildTestVariants(uniqueId, discoveryRequest);
        return discoverTests(uniqueId);
    }

    @Override
    protected SpockExecutionContext createExecutionContext(ExecutionRequest request) {
        return new SpockExecutionContext(request.getEngineExecutionListener());
    }

    private void buildTestVariants(UniqueId rootId, EngineDiscoveryRequest discoveryRequest) {
        variants.add(TestVariant.tapiCurrent(rootId, discoveryRequest));
        variants.add(TestVariant.selected(rootId, discoveryRequest));
        variants.add(TestVariant.tapiTargetLoaded(rootId, discoveryRequest));
    }

    private TestDescriptor discoverTests(UniqueId uniqueId) {
        EngineDescriptor rootDescriptor = new SpockEngineDescriptor(uniqueId, RunContext.get());
        for (TestVariant testVariant : variants) {
            for (TestDescriptor test : testVariant.discover(delegateEngine).getChildren()) {
                rootDescriptor.addChild(test);
            }
        }
        return rootDescriptor;
    }
}

class TestVariant {
    private final UniqueId id;
    private final EngineDiscoveryRequest discoveryRequest;
    private final Map<String, String> systemProperties = new HashMap<String, String>();

    static TestVariant tapiCurrent(UniqueId rootId, EngineDiscoveryRequest discoveryRequest) {
        TestVariant testVariant = new TestVariant(rootId, "tapiCurrent", new ToolingApiCurrentDiscoveryRequest(discoveryRequest));
        testVariant.systemProperties.put(VERSIONS_SYSPROP_NAME, CoverageContext.DEFAULT.selector);
        return testVariant;
    }

    static TestVariant selected(UniqueId rootId, EngineDiscoveryRequest discoveryRequest) {
        return new TestVariant(rootId, "selected", discoveryRequest);
    }

    static TestVariant tapiTargetLoaded(UniqueId rootId, EngineDiscoveryRequest discoveryRequest) {
        TestVariant testVariant = new TestVariant(rootId, "tapi", new ToolingApiClassloaderDiscoveryRequest(discoveryRequest));
        testVariant.systemProperties.put("org.gradle.integtest.currentVersion", GradleVersion.current().getVersion());
        return testVariant;
    }

    private TestVariant(UniqueId rootId, String variant, EngineDiscoveryRequest request) {
        this.id = rootId.append("variant", variant);
        this.discoveryRequest = request;
    }

    TestDescriptor discover(final TestEngine discoveryEngine) {
        return SystemProperties.getInstance().withSystemProperties(systemProperties, new Factory<TestDescriptor>() {
            @Override
            public TestDescriptor create() {
                return discoveryEngine.discover(discoveryRequest, id);
            }
        });
    }
}

class DelegatingDiscoveryRequest implements EngineDiscoveryRequest {
    private final EngineDiscoveryRequest delegate;
    private final List<ClassSelector> selectors = new ArrayList<ClassSelector>();

    DelegatingDiscoveryRequest(EngineDiscoveryRequest delegate) {
        this.delegate = delegate;
    }

    void addSelector(ClassSelector selector) {
        selectors.add(selector);
    }

    @Override
    public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> selectorType) {
        if (!selectorType.isAssignableFrom(ClassSelector.class)) {
            return Collections.emptyList();
        }
        return Cast.uncheckedCast(selectors);
    }

    @Override
    public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> filterType) {
        return delegate.getFiltersByType(filterType);
    }

    @Override
    public ConfigurationParameters getConfigurationParameters() {
        return delegate.getConfigurationParameters();
    }

    public EngineDiscoveryListener getDiscoveryListener() {
        return delegate.getDiscoveryListener();
    }
}

class ToolingApiClassloaderDiscoveryRequest extends DelegatingDiscoveryRequest {

    private static final GradleVersion MIN_LOADABLE_TAPI_VERSION = GradleVersion.version("2.6");

    private ToolingApiDistribution toolingApi;

    ToolingApiClassloaderDiscoveryRequest(EngineDiscoveryRequest delegate) {
        super(delegate);
        String toolingApiVersionToLoad = getToolingApiVersionToLoad();
        if (toolingApiVersionToLoad == null) {
            return;
        }

        // This is necessary because for the Tooling Api cross version tests, NativeServices
        // can get initialized in a different classloader, which then makes it broken and unusable
        // in the test class (because the native shared library is loaded from another classloader).
        // By initializing it here, we ensure that it is loaded from the classloader the test engine
        // also uses.
        NativeServicesTestFixture.initialize();

        for (ClassSelector selector : delegate.getSelectorsByType(ClassSelector.class)) {
            if (ToolingApiSpecification.class.isAssignableFrom(selector.getJavaClass())) {
                ClassLoader classLoader = ToolingApiClassLoaderProvider.getToolingApiClassLoader(getToolingApi(toolingApiVersionToLoad), selector.getJavaClass());
                try {
                    addSelector(DiscoverySelectors.selectClass(classLoader.loadClass(selector.getClassName())));
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private ToolingApiDistribution getToolingApi(String versionToTestAgainst) {
        if (toolingApi == null) {
            toolingApi = new ToolingApiDistributionResolver().withDefaultRepository().resolve(versionToTestAgainst);
        }
        return toolingApi;
    }

    private String getToolingApiVersionToLoad() {
        String candidateTapiVersion = System.getProperty(VERSIONS_SYSPROP_NAME);
        if (CoverageContext.LATEST.selector.equals(candidateTapiVersion)) {
            ReleasedVersionDistributions releasedVersions = new ReleasedVersionDistributions(IntegrationTestBuildContext.INSTANCE);
            return releasedVersions.getMostRecentRelease().getVersion().getVersion();
        }
        if (GradleVersion.version(candidateTapiVersion).compareTo(MIN_LOADABLE_TAPI_VERSION) >= 0) {
            return candidateTapiVersion;
        }
        return null;
    }
}

class ToolingApiCurrentDiscoveryRequest extends DelegatingDiscoveryRequest {

    ToolingApiCurrentDiscoveryRequest(EngineDiscoveryRequest delegate) {
        super(delegate);
        String candidateTapiVersion = System.getProperty(VERSIONS_SYSPROP_NAME);
        if (!CoverageContext.LATEST.selector.equals(candidateTapiVersion)) {
            return;
        }
        for (ClassSelector selector : delegate.getSelectorsByType(ClassSelector.class)) {
            if (ToolingApiSpecification.class.isAssignableFrom(selector.getJavaClass())) {
                addSelector(DiscoverySelectors.selectClass(selector.getJavaClass()));
            }
        }
    }
}
