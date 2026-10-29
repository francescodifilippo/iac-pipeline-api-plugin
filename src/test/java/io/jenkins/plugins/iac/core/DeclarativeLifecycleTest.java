package io.jenkins.plugins.iac.core;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
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

/** Provision/await lifecycle as used by provider plugins: Declarative stage options with agent none. */
class DeclarativeLifecycleTest {
 @RegisterExtension
 private final JenkinsSessionExtension sessions=new JenkinsSessionExtension();

 static final List<String> SUBMITS=new CopyOnWriteArrayList<>();
 static final Queue<String> STATUS=new ConcurrentLinkedQueue<>();
 static final AtomicInteger STATUS_CALLS=new AtomicInteger();
 static volatile boolean submitCompletes;

 @BeforeEach void reset(){SUBMITS.clear();STATUS.clear();STATUS_CALLS.set(0);submitCompletes=true;}

 @Test void planThenApplyOnSameTargetWithDefaultKeysSubmitsTwice() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"plan-apply",
     "stage('plan') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'plan') }\n steps { echo 'plan body' } }\n"+
     "stage('apply') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertSuccess(p);
   assertEquals(2,SUBMITS.size(),SUBMITS.toString());
   assertTrue(SUBMITS.get(0).startsWith("plan:"));
   assertTrue(SUBMITS.get(1).startsWith("apply:"));
   j.assertLogContains("plan body",b);
   j.assertLogContains("apply body",b);
   String planKey=Identifiers.defaultOperationKey("fake","ws-1",Map.of("mode","plan"));
   String applyKey=Identifiers.defaultOperationKey("fake","ws-1",Map.of("mode","apply"));
   OperationStoreAction store=b.getAction(OperationStoreAction.class);
   assertEquals("run-1",store.get(planKey).remoteId());
   assertEquals("run-2",store.get(applyKey).remoteId());
  });
 }

 @Test void duplicateExplicitOperationKeyFailsClearly() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"duplicate-key",
     "stage('plan') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'plan', operationKey: 'deploy') }\n steps { echo 'plan body' } }\n"+
     "stage('apply') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'apply', operationKey: 'deploy') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,p);
   assertEquals(1,SUBMITS.size(),SUBMITS.toString());
   j.assertLogContains("operationKey 'deploy' is already used by another provision step in this build",b);
   j.assertLogContains("Set a distinct operationKey in the stage options",b);
   j.assertLogContains("plan body",b);
   j.assertLogNotContains("apply body",b);
  });
 }

 @Test void asyncProvisionThenAwaitRetriesTransientStatusError() throws Throwable {
  sessions.then(j -> {
   submitCompletes=false;
   STATUS.add("503");STATUS.add("done");
   WorkflowJob p=pipeline(j,"async-await",
     "stage('submit') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'apply', operationKey: 'deploy', waitForCompletion: false) }\n steps { echo 'submitted' } }\n"+
     "stage('await') { options { fakeAwait(server: 'conn', operationKey: 'deploy', pollingSeconds: 5) }\n steps { echo 'awaited' } }");
   WorkflowRun b=j.buildAndAssertSuccess(p);
   assertEquals(1,SUBMITS.size(),SUBMITS.toString());
   assertEquals(2,STATUS_CALLS.get());
   j.assertLogContains("submitted",b);
   j.assertLogContains("transient error polling ID run-1 (attempt 1/5)",b);
   j.assertLogContains("IaC API HTTP 503 for GET /runs/run-1",b);
   j.assertLogContains("awaited",b);
   assertEquals("applied",b.getAction(OperationStoreAction.class).get("deploy").status());
  });
 }

 @Test void stopAfterBodyStartedCancelsBody() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"stop-body",
     "stage('apply') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'apply') }\n steps { script { try { sleep 300; echo 'after sleep' } catch (e) { echo \"body cancelled: ${e}\"; throw e } } } }");
   WorkflowRun b=p.scheduleBuild2(0).waitForStart();
   j.waitForMessage("Sleeping for",b);
   RemoteExecution execution=null;
   for(StepExecution x:b.getExecution().getCurrentExecutions(false).get())
    if(x instanceof RemoteExecution r)execution=r;
   assertNotNull(execution);
   execution.stop(new FlowInterruptedException(Result.ABORTED));
   j.assertBuildStatus(Result.ABORTED,j.waitForCompletion(b));
   j.assertLogNotContains("after sleep",b);
   j.assertLogContains("body cancelled: org.jenkinsci.plugins.workflow.steps.FlowInterruptedException",b);
   assertTrue(b.getExecution().getCurrentExecutions(false).get().isEmpty(),"no step left running");
  });
 }

 @Test void resumesOwnOperationAfterRestart() throws Throwable {
  sessions.then(j -> {
   submitCompletes=false;
   WorkflowJob p=pipeline(j,"resume",
     "stage('apply') { options { fakeProvision(server: 'conn', workspaceId: 'ws-1', mode: 'apply', pollingSeconds: 5) }\n steps { echo 'after resume' } }");
   WorkflowRun b=p.scheduleBuild2(0).waitForStart();
   j.waitForMessage("accepted remote ID run-1",b);
  });
  sessions.then(j -> {
   STATUS.add("done");
   WorkflowRun b=j.jenkins.getItemByFullName("resume",WorkflowJob.class).getBuildByNumber(1);
   j.assertBuildStatusSuccess(j.waitForCompletion(b));
   assertEquals(1,SUBMITS.size(),"must poll the operation it submitted, not resubmit");
   j.assertLogContains("after resume",b);
   j.assertLogNotContains("already used",b);
  });
 }

 private static WorkflowJob pipeline(JenkinsRule j,String name,String stages) throws Exception {
  WorkflowJob p=j.createProject(WorkflowJob.class,name);
  p.setDefinition(new CpsFlowDefinition("pipeline {\n agent none\n stages {\n"+stages+"\n }\n}",true));
  return p;
 }

 @TestExtension public static final class FakeBackend implements IacBackend {
  @Override public String id(){return "fake";}
  @Override public boolean supportsIdempotentSubmit(){return true;}
  @Override public JobResult submit(SubmissionRequest request,Run<?,?> run){
   SUBMITS.add(request.parameters().get("mode")+":"+request.requestToken());
   String id="run-"+SUBMITS.size();
   return submitCompletes?new JobResult(id,"applied",true,true,false):new JobResult(id,"pending",false,false,false);
  }
  @Override public JobResult status(RemoteOperation operation,Run<?,?> run) throws Exception {
   STATUS_CALLS.incrementAndGet();
   String next=STATUS.poll();
   if("503".equals(next))throw new IacHttpException(503,"GET","/runs/"+operation.remoteId());
   if("done".equals(next))return new JobResult(operation.remoteId(),"applied",true,true,false);
   return new JobResult(operation.remoteId(),"running",false,false,false);
  }
 }

 public static final class FakeProvisionStep extends AbstractProvisionStep {
  private String mode="plan";
  @DataBoundConstructor public FakeProvisionStep(String server,String workspaceId){super(server,workspaceId);}
  public String getServer(){return getConnectionId();}
  public String getWorkspaceId(){return getTargetId();}
  public String getMode(){return mode;}
  @DataBoundSetter public void setMode(String value){mode=value;}
  @Override protected String provider(){return "fake";}
  @Override protected Map<String,String> parameters(){return Map.of("mode",mode);}
  @TestExtension public static final class DescriptorImpl extends StepDescriptor {
   @Override public String getFunctionName(){return "fakeProvision";}
   @Override public boolean takesImplicitBlockArgument(){return true;}
   @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
  }
 }

 public static final class FakeAwaitStep extends AbstractAwaitStep {
  @DataBoundConstructor public FakeAwaitStep(String server,String operationKey){super(server,operationKey);}
  public String getServer(){return getConnectionId();}
  @Override protected String provider(){return "fake";}
  @TestExtension public static final class DescriptorImpl extends StepDescriptor {
   @Override public String getFunctionName(){return "fakeAwait";}
   @Override public boolean takesImplicitBlockArgument(){return true;}
   @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
  }
 }
}
