/*
* The MIT License (MIT)
*
* Copyright (c) 2014, Gregory Boissinot
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
*/

package org.jenkinsci.plugins.xunit;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.jenkinsci.lib.dtkit.model.InputMetric;
import org.jenkinsci.lib.dtkit.model.InputMetricXSL;
import org.jenkinsci.lib.dtkit.type.TestType;
import org.jenkinsci.plugins.xunit.service.NoTestFoundException;
import org.jenkinsci.plugins.xunit.service.XUnitConversionService;
import org.jenkinsci.plugins.xunit.service.XUnitLog;
import org.jenkinsci.plugins.xunit.service.XUnitReportProcessorService;
import org.jenkinsci.plugins.xunit.service.XUnitToolInfo;
import org.jenkinsci.plugins.xunit.service.XUnitTransformerCallable;
import org.jenkinsci.plugins.xunit.service.XUnitValidationService;
import org.jenkinsci.plugins.xunit.threshold.XUnitThreshold;
import org.jenkinsci.plugins.xunit.types.CustomType;
import org.jenkinsci.plugins.xunit.util.DownloadableResourceUtil;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Singleton;

import hudson.FilePath;
import hudson.Util;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import jenkins.model.Jenkins;

/**
 * @author Gregory Boissinot
 */
public class XUnitProcessor {

    /**
     * Parse generated JUnit report on a slave node.
     * <p>
     * This class is external to ensure serialisation is not broken as anonymous
     * class requires that whole outer class was serialisable too.
     **/
    private static final class ReportParserCallable extends jenkins.SlaveToMasterFileCallable<TestResult> {
        private static final long serialVersionUID = 1L;

        private final String junitFilePattern;
        private final long buildTime;
        private final long nowMaster;
        private final String processorId;

        public ReportParserCallable(long buildTime,
                                    @Nonnull String junitFilePattern,
                                    long nowMaster,
                                    String processorId) {
            this.buildTime = buildTime;
            this.junitFilePattern = junitFilePattern;
            this.nowMaster = nowMaster;
            this.processorId = processorId;
        }

        @Override
        public TestResult invoke(File ws, VirtualChannel channel) throws IOException {
            final long nowSlave = System.currentTimeMillis();
            File generatedJUnitDir = new File(new File(ws, XUnitDefaultValues.GENERATED_JUNIT_DIR), processorId);
            FileUtils.forceMkdir(generatedJUnitDir);
            FileSet fs = Util.createFileSet(generatedJUnitDir, junitFilePattern);
            DirectoryScanner ds = fs.getDirectoryScanner();
            String[] files = ds.getIncludedFiles();

            if (files.length == 0) {
                // no test result. Most likely a configuration error or fatal
                // problem
                return null;

            }
            return new TestResult(buildTime + (nowSlave - nowMaster), ds, true);
        }
    }

    private final TestType[] tools;
    private final XUnitThreshold[] thresholds;
    private final int thresholdMode;
    private final ExtraConfiguration extraConfiguration;
    private final String processorId;
    private XUnitLog logger;

    public XUnitProcessor(@Nonnull TestType[] tools,
                          @CheckForNull XUnitThreshold[] thresholds,
                          int thresholdMode,
                          @Nonnull ExtraConfiguration extraConfiguration) {
        if (tools == null) {
            throw new IllegalArgumentException("The tools section is required.");
        }
        if (extraConfiguration == null) {
            throw new IllegalArgumentException("The extra configuration is required.");
        }
        this.tools = Arrays.copyOf(tools, tools.length);
        this.thresholds = thresholds != null ? Arrays.copyOf(thresholds, thresholds.length) : new XUnitThreshold[0];
        this.thresholdMode = thresholdMode;
        this.extraConfiguration = extraConfiguration;
        this.processorId = UUID.randomUUID().toString();
    }

    public void process(Run<?, ?> build, FilePath workspace, TaskListener listener) throws IOException, InterruptedException {
        logger = new XUnitLog(listener);
        logger.info("Starting to record.");

        int processedReports = processTestsReport(build, workspace, listener);

        if (processedReports == 0) {
            logger.info("Skipping tests recording.");
            return;
        }

        TestResult testResult = recordTestResult(build, workspace, listener);

        processDeletion(workspace);

        Result result = getBuildStatus(testResult, build);
        logger.info("Setting the build status to " + result);
        build.setResult(result);
        logger.info("Stopping recording.");
    }

    private int processTestsReport(Run<?, ?> build,
                                       FilePath workspace,
                                       TaskListener listener) throws IOException, InterruptedException {
        int processedReports = 0;

        XUnitReportProcessorService xUnitReportService = new XUnitReportProcessorService(logger);
        for (TestType tool : tools) {
            logger.info("Processing " + tool.getDescriptor().getDisplayName());

            if (!isEmptyGivenPattern(xUnitReportService, tool)) {
                XUnitToolInfo xUnitToolInfo = buildXUnitToolInfo(tool, build, workspace, listener);
                XUnitTransformerCallable xUnitTransformer = newXUnitTransformer(xUnitToolInfo);
                try {
                    processedReports += workspace.act(xUnitTransformer);
                } catch (NoTestFoundException e) {
                    if (xUnitToolInfo.isSkipNoTestFiles()) {
                        logger.info(e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
        }

        return processedReports;
    }

    private boolean isEmptyGivenPattern(XUnitReportProcessorService xUnitReportService, TestType tool) {
        return xUnitReportService.isEmptyPattern(tool.getPattern());
    }

    private String getExpandedResolvedPattern(TestType tool,
                                              Run<?, ?> build,
                                              TaskListener listener) throws IOException, InterruptedException {
        String newExpandedPattern = tool.getPattern();
        newExpandedPattern = newExpandedPattern.replaceAll("[\t\r\n]+", " ");
        return Util.replaceMacro(newExpandedPattern, build.getEnvironment(listener));
    }

    private XUnitToolInfo buildXUnitToolInfo(final TestType tool,
                                             final Run<?, ?> build,
                                             final FilePath workspace,
                                             final TaskListener listener) throws IOException, InterruptedException {

        InputMetric inputMetric = tool.getInputMetric();
        inputMetric = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(TaskListener.class).toInstance(listener);
                bind(XUnitLog.class).in(Singleton.class);
                bind(XUnitValidationService.class).in(Singleton.class);
                bind(XUnitConversionService.class).in(Singleton.class);
            }
        }).getInstance(inputMetric.getClass());

        String xslContent = null;
        if (tool instanceof CustomType) {
            xslContent = getCustomStylesheet(tool, build, workspace, listener);
        } else if (inputMetric instanceof InputMetricXSL) {
            xslContent = getUserStylesheet(tool);
        }

        final String pattern = getExpandedResolvedPattern(tool, build, listener);

        return new XUnitToolInfo(inputMetric, pattern, tool.isSkipNoTestFiles(), tool.isFailIfNotNew(), tool.isDeleteOutputFiles(), tool.isStopProcessingIfError(), build.getTimeInMillis(), this.extraConfiguration.getTestTimeMargin(), xslContent);
    }

    private String getUserStylesheet(final TestType tool) throws IOException, InterruptedException {
        File userContent = new File(Jenkins.getActiveInstance().getRootDir(), "userContent");

        InputMetricXSL inputMetricXSL = (InputMetricXSL) tool.getInputMetric();
        FilePath userXSLFilePath = new FilePath(new File(userContent, inputMetricXSL.getUserContentXSLDirRelativePath()));
        if (!userXSLFilePath.exists()) {
            return null;
        }

        logger.info("Using the custom user stylesheet in JENKINS_HOME.");
        try (InputStream is = userXSLFilePath.read()) {
            return IOUtils.toString(is, "UTF-8");
        }
    }

    private String getCustomStylesheet(final TestType tool,
                                       final Run<?, ?> build,
                                       final FilePath workspace,
                                       final TaskListener listener) throws IOException, InterruptedException {

        final String customXSLPath = Util.replaceMacro(((CustomType) tool).getCustomXSL(), build.getEnvironment(listener));
        
        // Try URL
        if (DownloadableResourceUtil.isURL(customXSLPath)) {
            return DownloadableResourceUtil.download(customXSLPath);
        }

        // Try full path on master
        FilePath customXSLFilePath = new FilePath(new File(customXSLPath));
        if (!customXSLFilePath.exists()) {
            // Try full path on slave
            customXSLFilePath = new FilePath(workspace.getChannel(), customXSLPath);
            if (!customXSLFilePath.exists()) {
                // Try from workspace
                customXSLFilePath = workspace.child(customXSLPath);
                if (!customXSLFilePath.exists()) {
                    throw new FileNotFoundException(Messages.xUnitProcessor_xslFileNotFound(customXSLPath));
                }
            }
        }

        try (InputStream is = customXSLFilePath.read()) {
            return IOUtils.toString(is, "UTF-8");
        }
    }

    private XUnitTransformerCallable newXUnitTransformer(final XUnitToolInfo xUnitToolInfo) {
        // TODO why use Guice in this manner it's the quite the same of
        // instantiate classes directly
        XUnitTransformerCallable transformer = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(XUnitToolInfo.class).toInstance(xUnitToolInfo);
                bind(XUnitValidationService.class).in(Singleton.class);
                bind(XUnitConversionService.class).in(Singleton.class);
                bind(XUnitLog.class).toInstance(logger);
                bind(XUnitReportProcessorService.class).in(Singleton.class);
            }
        }).getInstance(XUnitTransformerCallable.class);
        transformer.setProcessorId(processorId);
        return transformer;
    }

    private TestResult getPreviousTestResult(Run<?, ?> build) {
        Run<?, ?> previousBuild = build.getPreviousCompletedBuild();
        if (previousBuild == null) {
            return null;
        }
        TestResultAction previousAction = previousBuild.getAction(TestResultAction.class);
        if (previousAction == null) {
            return null;
        }
        return previousAction.getResult();
    }

    private TestResult recordTestResult(Run<?, ?> build,
                                        FilePath workspace,
                                        TaskListener listener) throws IOException, InterruptedException {
        TestResultAction existingAction = build.getAction(TestResultAction.class);
        final long buildTime = build.getTimestamp().getTimeInMillis();
        final long nowMaster = System.currentTimeMillis();

        TestResult result = getTestResult(workspace, "**/TEST-*.xml", buildTime, nowMaster);
        if (result != null) {
            TestResultAction action;
            if (existingAction == null) {
                action = new TestResultAction(build, result, listener);
            } else {
                action = existingAction;
                // TODO remove when move to junit 1.24
//              action.mergeResult(result, listener);
                merge(action, result, listener);
            }

            result.tally(); // force re-calculus of counters
            if (result.getPassCount() == 0 && result.getFailCount() == 0) {
                logger.warn(Messages.xUnitProcessor_emptyReport());
            }

            if (existingAction == null) {
                build.addAction(action);
            }
        }

        return result;
    }

    private void merge(TestResultAction action, TestResult result, TaskListener listener) {
        try {
            // move to reflection to bypass sandbox
            Method mergeMethod = TestResultAction.class.getDeclaredMethod("mergeResult", TestResult.class, TaskListener.class);
            mergeMethod.setAccessible(true);
            mergeMethod.invoke(action, result, listener);
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new IllegalStateException("Impossible to merge JUnit result to previous steps");
        }
    }

    /**
     * Gets a Test result object (a new one if any)
     *
     * @param workspace the build's workspace
     * @param junitFilePattern the JUnit search pattern
     * @param buildTime the build time
     * @param nowMaster the time on master
     * @return the test result object
     * @throws InterruptedException
     * @throws IOException
     */
    private TestResult getTestResult(final FilePath workspace,
                                     final String junitFilePattern,
                                     final long buildTime,
                                     final long nowMaster) throws IOException, InterruptedException {

        return workspace.act(new ReportParserCallable(buildTime, junitFilePattern, nowMaster, processorId));
    }

    @Nonnull
    private Result getBuildStatus(TestResult result, Run<?, ?> build) {
        Result curResult = processResultThreshold(result, build);
        Result previousResultStep = build.getResult();
        if (previousResultStep == null) {
            return curResult;
        }
        if (previousResultStep != Result.NOT_BUILT && previousResultStep.isWorseOrEqualTo(curResult)) {
            curResult = previousResultStep;
        }
        return curResult;
    }

    @Nonnull
    private Result processResultThreshold(TestResult testResult, Run<?, ?> build) {
        TestResult previousTestResult = getPreviousTestResult(build);

        if (thresholds != null) {
            for (XUnitThreshold threshold : thresholds) {
                logger.info(Messages.xUnitProcessor_checkThreshold(threshold.getDescriptor().getDisplayName()));
                Result result;
                if (XUnitDefaultValues.MODE_PERCENT == thresholdMode) {
                    result = threshold.getResultThresholdPercent(logger, build, testResult, previousTestResult);
                } else {
                    result = threshold.getResultThresholdNumber(logger, build, testResult, previousTestResult);
                }
                if (result.isWorseThan(Result.SUCCESS)) {
                    return result;
                }
            }
        }

        return Result.SUCCESS;
    }

    private void processDeletion(FilePath workspace) throws IOException, InterruptedException {
        FilePath generatedJUnitDir = workspace.child(XUnitDefaultValues.GENERATED_JUNIT_DIR).child(processorId);

        boolean keepJUnitDirectory = false;
        for (TestType tool : tools) {
            InputMetric inputMetric = tool.getInputMetric();

            if (tool.isDeleteOutputFiles()) {
                generatedJUnitDir.child(inputMetric.getToolName()).deleteRecursive();
            } else {
                // Mark the tool file parent directory to no deletion
                keepJUnitDirectory = true;
            }
        }
        if (!keepJUnitDirectory) {
            generatedJUnitDir.deleteRecursive();
        }
    }

}
