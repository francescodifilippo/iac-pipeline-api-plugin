package io.jenkins.plugins.iac.core;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** A provider-style stage option built on AsyncBlockExecution: polling, failure, restart and abort, with agent none. */
class AsyncBlockExecutionTest {
 @RegisterExtension
 private final JenkinsSessionExtension sessions=new JenkinsSessionExtension();
 /** Rounds of advance run so far, in every Jenkins session of a test. */
 static final AtomicInteger ROUNDS=new AtomicInteger();

 @BeforeEach void reset(){ROUNDS.set(0);}

 @Test void pollsUntilReadyThenRunsTheBodyOnce() throws Throwable {
  sessions.then(j -> {
   WorkflowRun b=j.buildAndAssertSuccess(pipeline(j,"ready","fakeSetup(polls: 2)"));
   assertEquals(3,ROUNDS.get());
   j.assertLogContains("IaC fake: round 1 of 3, saved progress 1;",b);
   j.assertLogContains("IaC fake: round 3 of 3, saved progress 3;",b);
   assertEquals(1,count(JenkinsRule.getLog(b),"setup body"));
  });
 }

 @Test void anExceptionFailsTheStageWithoutTheBody() throws Throwable {
  sessions.then(j -> {
   WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,pipeline(j,"failing","fakeSetup(polls: 1, failAt: 2)"));
   j.assertLogContains("fake setup failed in round 2",b);
   j.assertLogNotContains("setup body",b);
   assertEquals(2,ROUNDS.get());
  });
 }

 @Test void aRestartWhilePollingResumesWithTheSavedProgress() throws Throwable {
  sessions.then(j -> {
   WorkflowRun b=pipeline(j,"restart","fakeSetup(polls: 1000)").scheduleBuild2(0).waitForStart();
   j.waitForMessage("round 2 of 1001",b);
  });
  sessions.then(j -> {
   WorkflowRun b=j.jenkins.getItemByFullName("restart",WorkflowJob.class).getBuildByNumber(1);
   FakeSetupExecution.finishNow=true;
   try{j.waitForCompletion(b);}finally{FakeSetupExecution.finishNow=false;}
   j.assertBuildStatusSuccess(b);
   // The resumed execution kept the progress it saved before the restart instead of starting again at 1.
   String log=JenkinsRule.getLog(b);
   assertEquals(1,count(log,"saved progress 1;"),log);
   assertEquals(1,count(log,"saved progress 2;"),log);
   assertTrue(log.contains("Resuming build"),log);
   assertEquals(1,count(JenkinsRule.getLog(b),"setup body"));
  });
 }

 @Test void aRestartDuringARoundRunsTheRoundAgain() throws Throwable {
  sessions.then(j -> {
   WorkflowRun b=pipeline(j,"interrupted","fakeSetup(polls: 1, holdAt: 1)").scheduleBuild2(0).waitForStart();
   // Shutting Jenkins down interrupts the round that is still waiting for its remote call.
   j.waitForMessage("holding round 1",b);
  });
  sessions.then(j -> {
   WorkflowRun b=j.jenkins.getItemByFullName("interrupted",WorkflowJob.class).getBuildByNumber(1);
   j.assertBuildStatusSuccess(j.waitForCompletion(b));
   String log=JenkinsRule.getLog(b);
   assertEquals(1,count(log,"saved progress 1;"),log);
   assertEquals(1,count(log,"saved progress 2;"),log);
   assertFalse(log.contains("InterruptedException"),log);
   assertEquals(1,count(log,"setup body"));
  });
 }

 @Test void aRoundThatPollsAfterTheShutdownInterruptDoesNotRunAgainUntilResumed() throws Throwable {
  sessions.then(j -> {
   WorkflowRun b=pipeline(j,"polling","fakeSetup(polls: 1, holdAt: 1, pollWhenInterrupted: true)").scheduleBuild2(0).waitForStart();
   j.waitForMessage("holding round 1",b);
  });
  sessions.then(j -> {
   WorkflowRun b=j.jenkins.getItemByFullName("polling",WorkflowJob.class).getBuildByNumber(1);
   j.assertBuildStatusSuccess(j.waitForCompletion(b));
   // A round scheduled by the interrupted execution of the first session would run after one second.
   Thread.sleep(2500);
   assertEquals(2,ROUNDS.get());
   assertEquals(1,count(JenkinsRule.getLog(b),"setup body"));
  });
 }

 @Test void abortStopsPollingWithoutTheBody() throws Throwable {
  sessions.then(j -> {
   WorkflowRun b=pipeline(j,"abort","fakeSetup(polls: 1000)").scheduleBuild2(0).waitForStart();
   j.waitForMessage("round 2 of 1001",b);
   b.doStop();
   j.assertBuildStatus(Result.ABORTED,j.waitForCompletion(b));
   int rounds=ROUNDS.get();
   Thread.sleep(2500);
   assertEquals(rounds,ROUNDS.get(),"no round after abort");
   j.assertLogNotContains("setup body",b);
  });
 }

 private static int count(String log,String text){return log.split(java.util.regex.Pattern.quote(text),-1).length-1;}
 private static WorkflowJob pipeline(JenkinsRule j,String name,String option) throws Exception {
  WorkflowJob p=j.createProject(WorkflowJob.class,name);
  p.setDefinition(new CpsFlowDefinition("pipeline {\n agent none\n stages {\n stage('setup') {\n options { "+option+
    " }\n steps { echo 'setup body' }\n }\n }\n}",true));
  return p;
 }

 public static final class FakeSetupStep extends Step {
  private int polls,failAt,holdAt;
  private boolean pollWhenInterrupted;
  @DataBoundConstructor public FakeSetupStep(){}
  public int getPolls(){return polls;}
  public int getFailAt(){return failAt;}
  public int getHoldAt(){return holdAt;}
  public boolean isPollWhenInterrupted(){return pollWhenInterrupted;}
  @DataBoundSetter public void setPolls(int v){polls=v;}
  @DataBoundSetter public void setFailAt(int v){failAt=v;}
  @DataBoundSetter public void setHoldAt(int v){holdAt=v;}
  @DataBoundSetter public void setPollWhenInterrupted(boolean v){pollWhenInterrupted=v;}
  @Override public StepExecution start(StepContext context){return new FakeSetupExecution(context,polls,failAt,holdAt,pollWhenInterrupted);}
  @TestExtension public static final class DescriptorImpl extends StepDescriptor {
   @Override public String getFunctionName(){return "fakeSetup";}
   @Override public boolean takesImplicitBlockArgument(){return true;}
   @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
  }
 }
 /** Ready after {@code polls} rounds that return false; counts its rounds in a saved field. */
 static final class FakeSetupExecution extends AsyncBlockExecution {
  private static final long serialVersionUID=1L;
  /** Makes the next round report ready, so that a resumed build ends quickly. */
  static volatile boolean finishNow;
  private final int polls,failAt,holdAt;
  private final boolean pollWhenInterrupted;
  private int progress;
  FakeSetupExecution(StepContext context,int polls,int failAt,int holdAt,boolean pollWhenInterrupted){
   super(context,"fake");this.polls=polls;this.failAt=failAt;this.holdAt=holdAt;this.pollWhenInterrupted=pollWhenInterrupted;
  }
  @Override protected int pollingSeconds(){return 1;}
  @Override protected boolean advance() throws Exception {
   int round=ROUNDS.incrementAndGet();
   if(round==failAt)throw new IllegalStateException("fake setup failed in round "+round);
   progress++;
   save();
   log("round "+round+" of "+(polls+1)+", saved progress "+progress+";");
   if(round==holdAt){
    log("holding round "+round);
    // Stands for a remote call that is still running; only an interrupt ends it.
    try{new CountDownLatch(1).await();}
    catch(InterruptedException e){
     // As a client that reports the interrupt as a transient error, so that the round asks to poll again.
     if(pollWhenInterrupted)return false;
     throw e;
    }
   }
   return finishNow||progress>polls;
  }
 }
}
