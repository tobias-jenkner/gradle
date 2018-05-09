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

package org.gradle.api.internal.tasks.testing.junitplatform;

import org.gradle.api.internal.tasks.testing.AbstractTestDescriptor;
import org.gradle.api.internal.tasks.testing.DefaultTestClassDescriptor;
import org.gradle.api.internal.tasks.testing.DefaultTestDescriptor;
import org.gradle.api.internal.tasks.testing.TestCompleteEvent;
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.TestStartEvent;
import org.gradle.api.internal.tasks.testing.junit.GenericJUnitTestEventAdapter;
import org.gradle.api.internal.tasks.testing.junit.TestClassExecutionListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.api.tasks.testing.TestResult.ResultType;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.time.Clock;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import javax.annotation.Nullable;
import java.util.Optional;

import static org.gradle.api.internal.tasks.testing.junitplatform.VintageTestNameAdapter.*;
import static org.gradle.api.tasks.testing.TestResult.ResultType.SKIPPED;
import static org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL;

public class JUnitPlatformTestExecutionListener implements TestExecutionListener {
    private final TestResultProcessor resultProcessor;
    private final Clock clock;
    private TestPlan currentTestPlan;

    JUnitPlatformTestExecutionListener(TestResultProcessor resultProcessor, Clock clock) {
        this.resultProcessor = resultProcessor;
        this.clock = clock;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        this.currentTestPlan = testPlan;
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        this.currentTestPlan = null;
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        TestDescriptorInternal descriptor = getDescriptor(testIdentifier);
        resultProcessor.started(descriptor, startEvent(testIdentifier));
        reportSkipped(testIdentifier, reason);
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        resultProcessor.started(getDescriptor(testIdentifier), startEvent(testIdentifier));
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        switch (testExecutionResult.getStatus()) {
            case SUCCESSFUL:
                resultProcessor.completed(testIdentifier.getUniqueId(), completeEvent(null));
                break;
            case FAILED:
                resultProcessor.failure(testIdentifier.getUniqueId(), testExecutionResult.getThrowable().get());
                break;
            case ABORTED:
                reportSkipped(testIdentifier, testExecutionResult.getThrowable().map(Throwable::getMessage).orElse(""));
                break;
            default:
                throw new AssertionError("Invalid Status: " + testExecutionResult.getStatus());
        }
    }

    private void reportSkipped(TestIdentifier testIdentifier, String reason) {
        ResultType resultType = null;
        if (isLeafTest(testIdentifier)) {
            resultType = SKIPPED;
        } else {
            currentTestPlan.getChildren(testIdentifier).forEach(child -> executionSkipped(child, reason));
        }
        resultProcessor.completed(testIdentifier.getUniqueId(), completeEvent(resultType));
    }

    private TestCompleteEvent completeEvent(ResultType resultType) {
        return new TestCompleteEvent(clock.getCurrentTime(), resultType);
    }

    private TestStartEvent startEvent(TestIdentifier testIdentifier) {
        return new TestStartEvent(clock.getCurrentTime(), testIdentifier.getParentId().orElse(null));
    }

    private boolean isLeafTest(TestIdentifier identifier) {
        return currentTestPlan.getChildren(identifier).isEmpty();
    }

    private TestDescriptorInternal getDescriptor(TestIdentifier node) {
        Optional<TestIdentifier> parent = currentTestPlan.getParent(node);
        if (node.isContainer() || !parent.isPresent()) {
            return new DefaultTestClassDescriptor(node.getUniqueId(), computeClassName(node), node.getDisplayName());
        } else {
            return new DefaultTestDescriptor(node.getUniqueId(), computeClassName(parent.get()), computeChildName(node), parent.get().getDisplayName(), node.getDisplayName());
        }
    }

    private String computeClassName(TestIdentifier node) {
        return node.getSource()
            .filter(ClassSource.class::isInstance)
            .map(source -> ((ClassSource) source).getClassName())
            .orElseGet(node::getLegacyReportingName);
    }

    private String computeChildName(TestIdentifier child) {
        return child.getSource()
            .filter(MethodSource.class::isInstance)
            .map(source -> ((MethodSource) source).getMethodName())
            .orElseGet(child::getLegacyReportingName);
    }
}
