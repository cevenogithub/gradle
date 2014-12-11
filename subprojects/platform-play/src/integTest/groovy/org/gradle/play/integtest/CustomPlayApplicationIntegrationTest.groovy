/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.play.integtest
import org.gradle.play.integtest.fixtures.app.CustomPlayApp
import org.gradle.play.integtest.fixtures.app.PlayApp

import static org.gradle.integtests.fixtures.UrlValidator.*

class CustomPlayApplicationIntegrationTest extends AbstractPlayAppIntegrationTest {
    PlayApp playApp = new CustomPlayApp()

    @Override
    def getPluginsBlock() {
        return super.getPluginsBlock() + """
            plugins {
                id 'play-coffeescript'
            }
        """
    }

    def setup() {
        buildFile << """
            repositories {
                maven {
                    name = "gradle-js"
                    url = "https://repo.gradle.org/gradle/javascript-public"
                }
            }
        """
    }

    @Override
    void verifyJar() {
        super.verifyJar()

        jar("build/jars/play/playBinary.jar").containsDescendants(
                "views/html/awesome/index.class",
                "special/strangename/Application.class",
                "models/DataType.class",
                "models/ScalaClass.class",
                "controllers/MixedJava.class",
                "controllers/PureJava.class",
                "public/javascripts/sample.js",
                "public/javascripts/test.js",
        )
    }

    @Override
    void verifyContent() {
        super.verifyContent()

        // Custom Routes
        assert playUrl("java/one").text.contains("<li>foo:1</li>")
        assert playUrl("scala/one").text.contains("<li>hello:1</li>")

        // Custom Assets
        assertUrlContent playUrl("assets/javascripts/test.js"), file("app/assets/javascripts/sample.js")
        assertUrlContent playUrl("assets/javascripts/sample.js"), file("app/assets/javascripts/sample.js")
    }
}