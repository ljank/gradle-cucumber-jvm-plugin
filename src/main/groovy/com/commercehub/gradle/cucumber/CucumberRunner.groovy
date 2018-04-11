package com.commercehub.gradle.cucumber

import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool
import net.masterthought.cucumber.Configuration
import net.masterthought.cucumber.ReportParser
import net.masterthought.cucumber.json.Feature
import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.SourceSet

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Created by jgelais on 6/16/15.
 */
@Slf4j
class CucumberRunner {
    private static final String PLUGIN = '--plugin'
    private static final String TILDE = '~'
    private static final String TAGS = '--tags '

    /**
     * Result files can be quite large when including embedded images, etc. When checking if a result file is empty,
     * we don't want to process large files. A valid, empty result file is a JSON array and generally looks like
     * "[ ]". It could have various whitespace around it or inside the brackets (spaces, newlines, etc.), so we'll
     * pick a number sufficiently larger to account for that.
     * Any file with a size of this or larger won't be considered when checking for an empty result.
     */
    private static final int EMPTY_RESULT_FILE_MAX_SIZE_IN_BYTES = 16

    CucumberRunnerOptions options
    CucumberTestResultCounter testResultCounter
    List<String> jvmArgs
    Map<String, String> systemProperties
    Configuration configuration
    Logger gradleLogger

    CucumberRunner(CucumberRunnerOptions options, Configuration configuration,
                   CucumberTestResultCounter testResultCounter, List<String> jvmArgs, Map<String, String> systemProperties,
                   Logger gradleLogger) {
        this.options = options
        this.testResultCounter = testResultCounter
        this.configuration = configuration
        this.jvmArgs = jvmArgs
        this.systemProperties = systemProperties
        this.gradleLogger = gradleLogger
    }

    boolean run(SourceSet sourceSet, File resultsDir, File reportsDir) {
        AtomicBoolean hasFeatureParseErrors = new AtomicBoolean(false)

        def features = findFeatures(sourceSet)
        def batchSize = (int) Math.ceil(features.files.size() / options.maxParallelForks)

        testResultCounter.beforeSuite(features.files.size())
        GParsPool.withPool(options.maxParallelForks) {
            features.files.collate(batchSize).eachWithIndexParallel { featureBatch, batchId ->
                def runId = "feature-batch-${batchId}"
                File resultsFile = new File(resultsDir, "${runId}.json")
                File consoleOutLogFile = new File(resultsDir, "${runId}-out.log")
                File consoleErrLogFile = new File(resultsDir, "${runId}-err.log")
                File junitResultsFile = new File(resultsDir, "${runId}.xml")

                List<String> args = []
                applyGlueArguments(args)
                applyPluginArguments(args, resultsFile, junitResultsFile)
                applyDryRunArguments(args)
                applyMonochromeArguments(args)
                applyStrictArguments(args)
                applyTagsArguments(args)
                applySnippetArguments(args)
                featureBatch.each { featureFile ->
                    args << featureFile.absolutePath
                }

                new JavaProcessLauncher('cucumber.api.cli.Main', sourceSet.runtimeClasspath.toList())
                        .setArgs(args)
                        .setJvmArgs(jvmArgs)
                        .setConsoleOutLogFile(consoleOutLogFile)
                        .setConsoleErrLogFile(consoleErrLogFile)
                        .setSystemProperties(systemProperties)
                        .setGradleLogger(gradleLogger)
                        .execute()

                if (resultsFile.exists()) {
                    // if tags are used, they may exclude all features in a file, we don't want consider that an error
                    if (options.tags.empty || !isResultFileEmpty(resultsFile)) {
                        handleResult(resultsFile, consoleOutLogFile, hasFeatureParseErrors, sourceSet)
                    }
                } else {
                    hasFeatureParseErrors.set(true)
                    if (consoleErrLogFile.exists()) {
                        log.error(consoleErrLogFile.text)
                    }
                }
            }
        }

        if (hasFeatureParseErrors.get()) {
            throw new GradleException('One or more feature files failed to parse. See error output above')
        }

        testResultCounter.afterSuite()
        return !testResultCounter.hadFailures()
    }

    private boolean isResultFileEmpty(File resultsFile) {
        resultsFile.size() < EMPTY_RESULT_FILE_MAX_SIZE_IN_BYTES && resultsFile.text.replaceAll(/\s+/, '') == '[]'
    }

    List<Feature> parseFeatureResult(File jsonReport) {
        return new ReportParser(configuration).parseJsonFiles([jsonReport.absolutePath])
    }

    CucumberFeatureResult createResult(Feature feature) {
        CucumberFeatureResult result = new CucumberFeatureResult(
                totalScenarios: feature.passedScenarios + feature.failedScenarios,
                failedScenarios: feature.failedScenarios,
                totalSteps: feature.passedSteps + feature.failedSteps,
                failedSteps: feature.failedSteps,
                skippedSteps: feature.skippedSteps,
                pendingSteps: feature.pendingSteps,
                undefinedSteps: feature.undefinedSteps
        )

        return result
    }

    protected void applySnippetArguments(List<String> args) {
        args << '--snippets'
        args << options.snippets
    }

    protected void applyTagsArguments(List<String> args) {
        if (!options.tags.isEmpty()) {
            applyTagsToCheck(args)
            applyTagsToIgnore(args)
        }
    }

    private void applyTagsToCheck(List<String> args) {
        def tagsToCheck = ''
        def hasTags = false
        options.tags.each {
            if (!it.contains(TILDE)) {
                tagsToCheck += it + ','
                hasTags = true
            }
        }
        if (hasTags) {
            args << TAGS
            args << tagsToCheck[0..-2]
        }
    }

    private void applyTagsToIgnore(List<String> args) {
        options.tags.each {
            if (it.contains(TILDE)) {
                args << TAGS
                args << it
            }
        }
    }

    protected void applyStrictArguments(List<String> args) {
        if (options.isStrict) {
            args << '--strict'
        }
    }

    protected void applyMonochromeArguments(List<String> args) {
        if (options.isMonochrome) {
            args << '--monochrome'
        }
    }

    protected void applyDryRunArguments(List<String> args) {
        if (options.isDryRun) {
            args << '--dry-run'
        }
    }

    protected void applyPluginArguments(List<String> args, File resultsFile, File junitResultsFile) {
        args << PLUGIN
        args << 'pretty'
        args << PLUGIN
        args << "json:${resultsFile.absolutePath}"
        if (options.junitReport) {
            args << PLUGIN
            args << "junit:${junitResultsFile.absolutePath}"
        }
        if (!options.plugins.empty) {
            options.plugins.each {
                args << PLUGIN
                args << it
            }
        }
    }

    protected List<String> applyGlueArguments(List<String> args) {
        options.stepDefinitionRoots.each {
            args << '--glue'
            args << it
        }
    }

    protected FileTree findFeatures(SourceSet sourceSet) {
        sourceSet.resources.matching {
            options.featureRoots.each {
                include("${it}/**/*.feature")
            }
        }
    }

    private void handleResult(File resultsFile, File consoleOutLogFile,
                              AtomicBoolean hasFeatureParseErrors, SourceSet sourceSet) {
        List<CucumberFeatureResult> results = parseFeatureResult(resultsFile).collect {
            log.debug("Logging result for $it.name")
            createResult(it)
        }
        results.each { CucumberFeatureResult result ->
            testResultCounter.afterFeature(result)

            if (result.hadFailures()) {
                if (result.undefinedSteps > 0) {
                    hasFeatureParseErrors.set(true)
                }
                log.error('{}:\r\n {}', sourceSet.name, consoleOutLogFile.text)
            }
        }
    }
}
