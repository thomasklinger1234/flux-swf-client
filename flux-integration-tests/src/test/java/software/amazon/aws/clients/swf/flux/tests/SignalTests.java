package software.amazon.aws.clients.swf.flux.tests;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.aws.clients.swf.flux.step.Attribute;
import software.amazon.aws.clients.swf.flux.wf.Workflow;
import software.amazon.aws.clients.swf.flux.wf.graph.WorkflowGraph;
import software.amazon.aws.clients.swf.flux.wf.graph.WorkflowGraphBuilder;

import software.amazon.aws.clients.swf.flux.poller.signals.SignalType;
import software.amazon.aws.clients.swf.flux.step.StepApply;
import software.amazon.aws.clients.swf.flux.step.StepAttributes;
import software.amazon.aws.clients.swf.flux.step.StepResult;
import software.amazon.aws.clients.swf.flux.step.WorkflowStep;

/**
 * Tests that validate Flux's signal support.
 */
public class SignalTests extends WorkflowTestBase {
    private static final Logger log = LoggerFactory.getLogger(SignalTests.class);

    private static final String RESULT_CODE_THAT_CLOSES_WORKFLOW = "IMustBeForcedToClose";

    @Override
    List<Workflow> getWorkflowsForTest() {
        return Arrays.asList(new RequiresForcedResult(), new RetriesAFewTimes(), new RetriesOnceWithLongRetryTime());
    }

    @Test
    public void testRetriesExpectedNumberOfTimesBeforeForceResultSignal() throws InterruptedException {
        String uuid = UUID.randomUUID().toString();
        executeWorkflow(RequiresForcedResult.class, uuid, Collections.emptyMap());
        log.info("Sleeping for 6 seconds, there should be 1 attempt after this...");
        Thread.sleep(6000);
        Assert.assertEquals(1, AlwaysRetries.getAttemptCount());

        log.info("Sleeping for 20 seconds, there should be 1 more attempt after this...");
        Thread.sleep(20000);
        Assert.assertEquals(2, AlwaysRetries.getAttemptCount());

        // we need to know the activity name and the next attempt number (which is zero-based).
        signalWorkflowExecution(uuid, SignalType.FORCE_RESULT.getFriendlyName(),
                                String.format("{\"activityId\": \"%s\", \"resultCode\": \"%s\" }",
                                              String.format("%s_%s", AlwaysRetries.class.getSimpleName(), 2),
                                              RESULT_CODE_THAT_CLOSES_WORKFLOW));
        waitForWorkflowCompletion(uuid, Duration.ofSeconds(30));

        // since we forced the workflow to close, it should have closed without running the step again.
        Assert.assertEquals(2, AlwaysRetries.getAttemptCount());
    }

    @Test
    public void testRetryNowCausesEarlyRetry() throws InterruptedException {
        log.info("Running workflow with long retry time...");
        String uuid = UUID.randomUUID().toString();
        executeWorkflow(RetriesOnceWithLongRetryTime.class, uuid, Collections.emptyMap());
        log.info("Sleeping for 6 seconds, there should be 1 attempt after this...");
        Thread.sleep(6000);
        Assert.assertEquals(1, SucceedsOnRetryAttemptOne.getAttemptCount());

        log.info("Sleeping for 40 seconds, there should be 1 more attempt after this...");
        Thread.sleep(40000);
        Assert.assertEquals(2, SucceedsOnRetryAttemptOne.getAttemptCount());

        waitForWorkflowCompletion(uuid, Duration.ofSeconds(10));

        // It should have closed without running the step again.
        Assert.assertEquals(2, SucceedsOnRetryAttemptOne.getAttemptCount());

        log.info("Running workflow with long retry time again...");

        uuid = UUID.randomUUID().toString();
        executeWorkflow(RetriesOnceWithLongRetryTime.class, uuid, Collections.emptyMap());
        log.info("Sleeping for 6 seconds, there should be 1 more attempt after this...");
        Thread.sleep(6000);
        Assert.assertEquals(3, SucceedsOnRetryAttemptOne.getAttemptCount());

        // we need to know the activity name and the next attempt number (which is zero-based).
        signalWorkflowExecution(uuid, SignalType.RETRY_NOW.getFriendlyName(),
                                String.format("{\"activityId\": \"%s\"}",
                                              String.format("%s_%s", SucceedsOnRetryAttemptOne.class.getSimpleName(), 1)));

        log.info("Sleeping for 10 seconds, there should be 1 more attempt after this...");
        Thread.sleep(10000);
        Assert.assertEquals(4, SucceedsOnRetryAttemptOne.getAttemptCount());

        waitForWorkflowCompletion(uuid, Duration.ofSeconds(10));

        // It should have closed without running the step again.
        Assert.assertEquals(4, SucceedsOnRetryAttemptOne.getAttemptCount());
    }

    @Test
    public void testDelayRetryActuallyDelaysRetry() throws InterruptedException {
        String uuid = UUID.randomUUID().toString();
        executeWorkflow(RetriesAFewTimes.class, uuid, Collections.emptyMap());
        log.info("Sleeping for 6 seconds, there should be 1 attempt after this...");
        Thread.sleep(6000);
        Assert.assertEquals(1, SucceedsOnRetryAttemptTwo.getAttemptCount());

        // we need to know the activity name and the next attempt number (which is zero-based).
        signalWorkflowExecution(uuid, SignalType.DELAY_RETRY.getFriendlyName(),
                                String.format("{\"activityId\": \"%s\", \"delayInSeconds\": 40 }",
                                              String.format("%s_%s", SucceedsOnRetryAttemptTwo.class.getSimpleName(), 1)));

        // Note that delay-retry signals can take ~5 seconds to fully process since they involve multiple decision tasks;
        // the initial retry timer is cancelled as soon as the first signal is received,
        // but a second signal is sent to restart the retry timer, and that may be several seconds later.

        log.info("Sleeping for 16 seconds, there should not have been a second attempt after this...");
        Thread.sleep(16000);
        Assert.assertEquals(1, SucceedsOnRetryAttemptTwo.getAttemptCount());

        log.info("Sleeping for another 30 seconds, there should have been a second attempt after this...");
        Thread.sleep(30000);
        Assert.assertEquals(2, SucceedsOnRetryAttemptTwo.getAttemptCount());

        waitForWorkflowCompletion(uuid, Duration.ofSeconds(120));

        // Since the workflow only ends after its second retry attempt (third step execution), there should be three attempts now.
        Assert.assertEquals(3, SucceedsOnRetryAttemptTwo.getAttemptCount());
    }

    /**
     * Workflow with one step that retries a few times before it succeeds.
     */
    public static final class RetriesOnceWithLongRetryTime implements Workflow {
        private final WorkflowGraph graph;

        RetriesOnceWithLongRetryTime() {
            WorkflowStep stepOne = new SucceedsOnRetryAttemptOne();
            WorkflowGraphBuilder builder = new WorkflowGraphBuilder(stepOne, Collections.emptyMap());
            builder.alwaysClose(stepOne);
            graph = builder.build();
        }

        @Override
        public WorkflowGraph getGraph() {
            return graph;
        }
    }

    /**
     * Simple step that retries once with a 40-second retry time.
     */
    public static final class SucceedsOnRetryAttemptOne implements WorkflowStep {
        private static final AtomicInteger attemptCount = new AtomicInteger(0);

        /**
         * This step forces a bunch of the retry timing parameters so the test doesn't risk getting the timing wrong
         * due to jitter or backoff.
         */
        @StepApply(initialRetryDelaySeconds = 40, retriesBeforeBackoff = 6, jitterPercent = 0, maxRetryDelaySeconds = 40)
        public StepResult doThing(@Attribute(StepAttributes.RETRY_ATTEMPT) Long retryAttempt) {
            attemptCount.incrementAndGet();
            if (Long.valueOf(1).equals(retryAttempt)) {
                return StepResult.success("Succeeded on retry attempt 1");
            } else {
                return StepResult.retry("Haven't reached retry attempt 1 yet, retrying.");
            }
        }

        static int getAttemptCount() {
            return attemptCount.get();
        }
    }

    /**
     * Workflow with one step that retries a few times before it succeeds.
     */
    public static final class RetriesAFewTimes implements Workflow {
        private final WorkflowGraph graph;

        RetriesAFewTimes() {
            WorkflowStep stepOne = new SucceedsOnRetryAttemptTwo();
            WorkflowGraphBuilder builder = new WorkflowGraphBuilder(stepOne, Collections.emptyMap());
            builder.alwaysClose(stepOne);
            graph = builder.build();
        }

        @Override
        public WorkflowGraph getGraph() {
            return graph;
        }
    }

    /**
     * Simple step that retries twice with a 20-second retry time.
     */
    public static final class SucceedsOnRetryAttemptTwo implements WorkflowStep {
        private static final AtomicInteger attemptCount = new AtomicInteger(0);

        /**
         * This step forces a bunch of the retry timing parameters so the test doesn't risk getting the timing wrong
         * due to jitter or backoff.
         */
        @StepApply(initialRetryDelaySeconds = 20, retriesBeforeBackoff = 6, jitterPercent = 0, maxRetryDelaySeconds = 20)
        public StepResult doThing(@Attribute(StepAttributes.RETRY_ATTEMPT) Long retryAttempt) {
            attemptCount.incrementAndGet();
            if (Long.valueOf(2).equals(retryAttempt)) {
                return StepResult.success("Succeeded on retry attempt 2");
            } else {
                return StepResult.retry("Haven't reached retry attempt 2 yet, retrying.");
            }
        }

        static int getAttemptCount() {
            return attemptCount.get();
        }
    }

    /**
     * Workflow with one step that always retries. It can only succeed via ForceResultSignal
     */
    public static final class RequiresForcedResult implements Workflow {
        private final WorkflowGraph graph;

        RequiresForcedResult() {
            WorkflowStep stepOne = new AlwaysRetries();
            WorkflowGraphBuilder builder = new WorkflowGraphBuilder(stepOne, Collections.emptyMap());
            builder.closeOnCustom(stepOne, RESULT_CODE_THAT_CLOSES_WORKFLOW);
            graph = builder.build();
        }

        @Override
        public WorkflowGraph getGraph() {
            return graph;
        }
    }

    /**
     * Simple step that always retries.
     */
    public static final class AlwaysRetries implements WorkflowStep {
        private static final AtomicInteger attemptCount = new AtomicInteger(0);

        /**
         * This step forces a bunch of the retry timing parameters so the test doesn't risk getting the timing wrong
         * due to jitter or backoff.
         */
        @StepApply(initialRetryDelaySeconds = 20, retriesBeforeBackoff = 6, jitterPercent = 0, maxRetryDelaySeconds = 20)
        public StepResult doThing() {
            attemptCount.incrementAndGet();
            return StepResult.retry("Always retrying!");
        }

        static int getAttemptCount() {
            return attemptCount.get();
        }
    }
}
