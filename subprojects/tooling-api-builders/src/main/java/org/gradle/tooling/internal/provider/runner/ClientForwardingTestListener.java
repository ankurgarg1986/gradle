/*
 * Copyright 2015 the original author or authors.
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
package org.gradle.tooling.internal.provider.runner;

import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.initialization.BuildEventConsumer;
import org.gradle.tooling.internal.protocol.TestProgressEventVersion1;
import org.gradle.tooling.internal.provider.InternalFailure;
import org.gradle.tooling.internal.provider.InternalTestProgressEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Test listener that forwards all receiving events to the client via the provided {@code BuildEventConsumer} instance.
 */
class ClientForwardingTestListener implements TestListener {

    private final BuildEventConsumer eventConsumer;

    ClientForwardingTestListener(BuildEventConsumer eventConsumer) {
        this.eventConsumer = eventConsumer;
    }

    @Override
    public void beforeSuite(TestDescriptor suite) {
        eventConsumer.dispatch(new InternalTestProgressEvent(TestProgressEventVersion1.STRUCTURE_SUITE, TestProgressEventVersion1.OUTCOME_STARTED, System.currentTimeMillis(), toTestDescriptor(suite), null));
    }

    @Override
    public void afterSuite(TestDescriptor suite, TestResult result) {
        eventConsumer.dispatch(new InternalTestProgressEvent(TestProgressEventVersion1.STRUCTURE_SUITE, toOutcome(result), result.getEndTime(), toTestDescriptor(suite), toTestResult(result)));
    }

    @Override
    public void beforeTest(TestDescriptor test) {
        eventConsumer.dispatch(new InternalTestProgressEvent(TestProgressEventVersion1.STRUCTURE_ATOMIC, TestProgressEventVersion1.OUTCOME_STARTED, System.currentTimeMillis(), toTestDescriptor(test), null));
    }

    @Override
    public void afterTest(final TestDescriptor test, final TestResult result) {
        eventConsumer.dispatch(new InternalTestProgressEvent(TestProgressEventVersion1.STRUCTURE_ATOMIC, toOutcome(result), result.getEndTime(), toTestDescriptor(test), toTestResult(result)));
    }

    private String toOutcome(TestResult result) {
        TestResult.ResultType resultType = result.getResultType();
        switch (resultType) {
            case SUCCESS:
                return TestProgressEventVersion1.OUTCOME_SUCCEEDED;
            case SKIPPED:
                return TestProgressEventVersion1.OUTCOME_SKIPPED;
            case FAILURE:
                return TestProgressEventVersion1.OUTCOME_FAILED;
            default:
                throw new IllegalStateException("Unknown test result type: " + resultType);
        }
    }

    private static InternalTestProgressEvent.InternalTestDescriptor toTestDescriptor(TestDescriptor suite) {
        Object id = ((TestDescriptorInternal) suite).getId();
        String name = suite.getName();
        String className = suite.getClassName();
        Object parentId = suite.getParent() != null ? ((TestDescriptorInternal) suite.getParent()).getId() : null;
        return new InternalTestProgressEvent.InternalTestDescriptor(id, name, className, parentId);
    }

    private static InternalTestProgressEvent.InternalTestResult toTestResult(TestResult result) {
        return new InternalTestProgressEvent.InternalTestResult(result.getStartTime(), result.getEndTime(), convertExceptions(result.getExceptions()));
    }

    private static List<InternalFailure> convertExceptions(List<Throwable> exceptions) {
        List<InternalFailure> failures = new ArrayList<InternalFailure>(exceptions.size());
        for (Throwable exception : exceptions) {
            failures.add(InternalFailure.fromThrowable(exception));
        }
        return failures;
    }

}
