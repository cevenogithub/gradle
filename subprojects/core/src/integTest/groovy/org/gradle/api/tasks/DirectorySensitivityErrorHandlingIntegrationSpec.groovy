/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Unroll


class DirectorySensitivityErrorHandlingIntegrationSpec extends AbstractIntegrationSpec {

    @Unroll
    def "deprecation warning when @IgnoreEmptyDirectories is applied to an #nonDirectoryInput.annotation annotation"() {
        createAnnotatedInputFileTask(nonDirectoryInput)
        buildFile << """
            task taskWithInputs(type: TaskWithInputs) {
                input = ${nonDirectoryInput.value}
                outputFile = file("\${buildDir}/output")
            }
        """

        file('foo').createFile()

        given:
        executer.expectDocumentedDeprecationWarning("Property 'input' is annotated with @IgnoreEmptyDirectories that is not allowed for ${nonDirectoryInput.annotation} properties. " +
            "This behaviour has been deprecated and is scheduled to be removed in Gradle 7.0. " +
            "Execution optimizations are disabled due to the failed validation. " +
            "See https://docs.gradle.org/current/userguide/more_about_tasks.html#sec:up_to_date_checks for more details.")

        expect:
        succeeds("taskWithInputs")

        where:
        nonDirectoryInput << NonDirectoryInput.values()
    }

    @Unroll
    def "deprecation warning when @IgnoreEmptyDirectories is applied to an #output.annotation annotation"() {
        createAnnotatedOutputFileTask(output)
        buildFile << """
            task taskWithOutputs(type: TaskWithOutputs) {
                input = file('foo')
                output = ${output.value}
            }
        """

        file('foo').createFile()

        given:
        executer.expectDocumentedDeprecationWarning("Property 'output' is annotated with @IgnoreEmptyDirectories that is not allowed for ${output.annotation} properties. " +
            "This behaviour has been deprecated and is scheduled to be removed in Gradle 7.0. " +
            "Execution optimizations are disabled due to the failed validation. " +
            "See https://docs.gradle.org/current/userguide/more_about_tasks.html#sec:up_to_date_checks for more details.")

        expect:
        succeeds("taskWithOutputs")

        where:
        output << Output.values()
    }

    enum NonDirectoryInput {
        INPUTFILE(InputFile, 'File', 'file("foo")', "@${PathSensitive.class.simpleName}(${PathSensitivity.class.simpleName}.${PathSensitivity.RELATIVE.name()})"),
        INPUT(Input, 'String', '"foo"')

        String annotation
        String type
        String value
        String additionalAnnotations

        NonDirectoryInput(Class<?> annotation, String type, String value, String additionalAnnotations = '') {
            this.annotation = "@${annotation.simpleName}"
            this.type = type
            this.value = value
            this.additionalAnnotations = additionalAnnotations
        }
    }

    enum Output {
        OUTPUTFILE(OutputFile, 'File', 'file("${buildDir}/foo")', false),
        OUTPUTFILES(OutputFiles, 'FileCollection', 'files("${buildDir}/foo")', false),
        OUTPUTDIRECTORY(OutputDirectory, 'File', 'file("${buildDir}/foo")', true),
        OUTPUTDIRECTORIES(OutputDirectories, 'FileCollection', 'files("${buildDir}/foo")', true)

        String annotation
        String type
        String value
        String directoryType

        Output(Class<?> annotation, String type, String value, directoryType) {
            this.annotation = "@${annotation.simpleName}"
            this.type = type
            this.value = value
            this.directoryType = directoryType
        }
    }

    void createAnnotatedInputFileTask(NonDirectoryInput nonDirectoryInput) {
        buildFile << """
            @CacheableTask
            class TaskWithInputs extends DefaultTask {
                ${nonDirectoryInput.annotation}
                ${nonDirectoryInput.additionalAnnotations}
                @IgnoreEmptyDirectories
                ${nonDirectoryInput.type} input

                @InputFiles
                @PathSensitive(PathSensitivity.RELATIVE)
                FileCollection sources

                @OutputFile
                File outputFile

                public TaskWithInputs() {
                    sources = project.files().from { input }
                }

                @TaskAction
                void doSomething() {
                    outputFile.withWriter { writer ->
                        sources.each { writer.println it }
                    }
                }
            }
        """
    }

    void createAnnotatedOutputFileTask(Output output) {
        buildFile << """
            @CacheableTask
            class TaskWithOutputs extends DefaultTask {
                @InputFile
                @PathSensitive(PathSensitivity.RELATIVE)
                File input

                @InputFiles
                @PathSensitive(PathSensitivity.RELATIVE)
                FileCollection sources

                ${output.annotation}
                @IgnoreEmptyDirectories
                ${output.type} output

                @Internal
                FileCollection outputFiles

                public TaskWithOutputs() {
                    sources = project.files().from { input }
                    outputFiles = project.files().from { output }
                }

                @TaskAction
                void doSomething() {
                    outputFiles.each { outputFile ->
                        def file = ${output.directoryType ? 'new File(outputFile, "output")' : 'outputFile'}
                        file.parentFile.mkdirs()
                        file.withWriter { writer ->
                            sources.each { writer.println it }
                        }
                    }
                }
            }
        """
    }
}
