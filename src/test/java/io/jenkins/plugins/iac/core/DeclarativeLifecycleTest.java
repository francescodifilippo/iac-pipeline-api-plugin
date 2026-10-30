package io.jenkins.plugins.iac.core;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.LinkedHashMap;
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
 /** Calls of the fake resolve, as "targetId parameters". */
 static final List<String> RESOLVES=new CopyOnWriteArrayList<>();
 /** What operationMetadata and submit saw, as "targetId parameters". */
 static final List<String> METADATA=new CopyOnWriteArrayList<>();
 static final List<String> REQUESTS=new CopyOnWriteArrayList<>();
 /** Fake provider workspaces by name; "dup" is ambiguous. */
 static final Map<String,List<String>> WORKSPACES=Map.of("prod",List.of("ws-prod"),"dup",List.of("ws-a","ws-b"));
 static volatile boolean blockSubmit;

 @BeforeEach void reset(){
  SUBMITS.clear();STATUS.clear();STATUS_CALLS.set(0);submitCompletes=true;
  RESOLVES.clear();METADATA.clear();REQUESTS.clear();blockSubmit=false;
 }

 @Test void planThenApplyOnSameTargetWithDefaultKeysSubmitsTwice() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"plan-apply",
     "stage('plan') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'plan') }\n steps { echo 'plan body' } }\n"+
     "stage('apply') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertSuccess(p);
   assertEquals(2,SUBMITS.size(),SUBMITS.toString());
   assertTrue(SUBMITS.get(0).startsWith("plan:"));
   assertTrue(SUBMITS.get(1).startsWith("apply:"));
   j.assertLogContains("plan body",b);
   j.assertLogContains("apply body",b);
   j.assertLogNotContains("resolved",b);
   assertEquals(List.of("ws-1 {mode=plan}","ws-1 {mode=apply}"),REQUESTS);
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
     "stage('plan') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'plan', operationKey: 'deploy') }\n steps { echo 'plan body' } }\n"+
     "stage('apply') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'apply', operationKey: 'deploy') }\n steps { echo 'apply body' } }");
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
     "stage('submit') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'apply', operationKey: 'deploy', waitForCompletion: false) }\n steps { echo 'submitted' } }\n"+
     "stage('await') { options { fakeAwait(connection: 'conn', operationKey: 'deploy', pollingSeconds: 5) }\n steps { echo 'awaited' } }");
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
     "stage('apply') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'apply') }\n steps { script { try { sleep 300; echo 'after sleep' } catch (e) { echo \"body cancelled: ${e}\"; throw e } } } }");
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
     "stage('apply') { options { fakeProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'apply', pollingSeconds: 5) }\n steps { echo 'after resume' } }");
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

 @Test void provisionByNameResolvesOnceAndSubmitsResolvedId() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"by-name",
     "stage('apply') { options { fakeLookupProvision(connection: 'conn', workspaceName: 'prod', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertSuccess(p);
   assertEquals(List.of("null {workspaceName=prod, mode=apply}"),RESOLVES);
   // Resolution replaced the name with IDs: metadata, submit and the stored operation only see the result.
   assertEquals(List.of("ws-prod {mode=apply, orgId=org-1}"),METADATA);
   assertEquals(List.of("ws-prod {mode=apply, orgId=org-1}"),REQUESTS);
   assertEquals(1,SUBMITS.size(),SUBMITS.toString());
   j.assertLogContains("IaC fake: resolved target to ws-prod",b);
   j.assertLogContains("IaC fake: resolved orgId to org-1",b);
   j.assertLogContains("submitting target ws-prod",b);
   j.assertLogContains("apply body",b);
   String key=Identifiers.defaultOperationKey("fake",null,Map.of("workspaceName","prod","mode","apply"));
   RemoteOperation op=b.getAction(OperationStoreAction.class).get(key);
   assertEquals("ws-prod",op.targetId());
   assertEquals(Map.of("orgId","org-1"),op.metadata());
   assertEquals("run-1",op.remoteId());
  });
 }

 @Test void lookupStepWithIdBehavesAsBefore() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"by-id",
     "stage('apply') { options { fakeLookupProvision(connection: 'conn', workspaceId: 'ws-1', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertSuccess(p);
   assertEquals(List.of("ws-1 {mode=apply}"),RESOLVES);
   assertEquals(List.of("ws-1 {mode=apply}"),REQUESTS);
   j.assertLogNotContains("resolved",b);
   String key=Identifiers.defaultOperationKey("fake","ws-1",Map.of("mode","apply"));
   assertEquals("ws-1",b.getAction(OperationStoreAction.class).get(key).targetId());
  });
 }

 @Test void nameBasedAsyncProvisionThenAwait() throws Throwable {
  sessions.then(j -> {
   submitCompletes=false;
   STATUS.add("done");
   WorkflowJob p=pipeline(j,"by-name-await",
     "stage('submit') { options { fakeLookupProvision(connection: 'conn', workspaceName: 'prod', mode: 'apply', operationKey: 'deploy', waitForCompletion: false) }\n steps { echo 'submitted' } }\n"+
     "stage('await') { options { fakeAwait(connection: 'conn', operationKey: 'deploy', pollingSeconds: 5) }\n steps { echo 'awaited' } }");
   WorkflowRun b=j.buildAndAssertSuccess(p);
   assertEquals(1,RESOLVES.size(),"await does not resolve");
   assertEquals(1,SUBMITS.size(),SUBMITS.toString());
   j.assertLogContains("awaited",b);
   RemoteOperation op=b.getAction(OperationStoreAction.class).get("deploy");
   assertEquals("ws-prod",op.targetId());
   assertEquals("applied",op.status());
  });
 }

 @Test void resolutionFailureFailsStageWithoutRunningBody() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"no-match",
     "stage('apply') { options { fakeLookupProvision(connection: 'conn', workspaceName: 'missing', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,p);
   j.assertLogContains("No fake workspace named 'missing'",b);
   j.assertLogNotContains("apply body",b);
   p=pipeline(j,"ambiguous",
     "stage('apply') { options { fakeLookupProvision(connection: 'conn', workspaceName: 'dup', mode: 'apply') }\n steps { echo 'apply body' } }");
   b=j.buildAndAssertStatus(Result.FAILURE,p);
   j.assertLogContains("2 fake workspaces named 'dup'; use workspaceId",b);
   j.assertLogNotContains("apply body",b);
   assertEquals(2,RESOLVES.size());
   assertTrue(SUBMITS.isEmpty(),SUBMITS.toString());
   assertTrue(METADATA.isEmpty(),"nothing is stored before resolution succeeds");
   assertNull(b.getAction(OperationStoreAction.class).get(Identifiers.defaultOperationKey("fake",null,Map.of("workspaceName","dup","mode","apply"))));
  });
 }

 @Test void missingTargetFailsClearly() throws Throwable {
  sessions.then(j -> {
   WorkflowJob p=pipeline(j,"neither",
     "stage('apply') { options { fakeLookupProvision(connection: 'conn', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=j.buildAndAssertStatus(Result.FAILURE,p);
   j.assertLogContains("Set either workspaceId or workspaceName",b);
   j.assertLogNotContains("apply body",b);
   // A step without name support that leaves the ID blank, or omits its constructor argument (stage options
   // accept that), hits the default resolve.
   for(String args:List.of("workspaceId: '', ","")){
    p=pipeline(j,"no-id-"+args.length(),
      "stage('apply') { options { fakeProvision(connection: 'conn', "+args+"mode: 'apply') }\n steps { echo 'apply body' } }");
    b=j.buildAndAssertStatus(Result.FAILURE,p);
    j.assertLogContains("fake target id is required",b);
    j.assertLogNotContains("apply body",b);
   }
   assertTrue(SUBMITS.isEmpty(),SUBMITS.toString());
  });
 }

 @Test void restartAfterResolutionDuringSubmitResubmitsResolvedRequestOnce() throws Throwable {
  sessions.then(j -> {
   blockSubmit=true;
   WorkflowJob p=pipeline(j,"restart-resolved",
     "stage('apply') { options { fakeLookupProvision(connection: 'conn', workspaceName: 'prod', mode: 'apply') }\n steps { echo 'apply body' } }");
   WorkflowRun b=p.scheduleBuild2(0).waitForStart();
   j.waitForMessage("submitting target ws-prod",b);
  });
  sessions.then(j -> {
   blockSubmit=false;
   WorkflowRun b=j.jenkins.getItemByFullName("restart-resolved",WorkflowJob.class).getBuildByNumber(1);
   j.assertBuildStatusSuccess(j.waitForCompletion(b));
   j.assertLogContains("retrying interrupted submission with persisted request token",b);
   assertEquals(1,RESOLVES.size(),"resolution is persisted, not repeated");
   assertEquals(1,SUBMITS.size(),SUBMITS.toString());
   assertEquals(List.of("ws-prod {mode=apply, orgId=org-1}","ws-prod {mode=apply, orgId=org-1}"),REQUESTS,
     "blocked and resubmitted requests carry the resolved target and parameters");
   String key=Identifiers.defaultOperationKey("fake",null,Map.of("workspaceName","prod","mode","apply"));
   RemoteOperation op=b.getAction(OperationStoreAction.class).get(key);
   assertEquals("apply:"+op.requestToken(),SUBMITS.get(0),"same request token");
   assertEquals("run-1",op.remoteId());
   j.assertLogContains("apply body",b);
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
  @Override public ResolvedTarget resolve(String connectionId,String targetId,Map<String,String> parameters,Run<?,?> run) throws Exception {
   RESOLVES.add(targetId+" "+parameters);
   String name=parameters.get("workspaceName");
   if(name==null)return IacBackend.super.resolve(connectionId,targetId,parameters,run);
   List<String> ids=WORKSPACES.getOrDefault(name,List.of());
   if(ids.isEmpty())throw new IllegalArgumentException("No fake workspace named '"+name+"'");
   if(ids.size()>1)throw new IllegalArgumentException(ids.size()+" fake workspaces named '"+name+"'; use workspaceId");
   Map<String,String> resolved=new LinkedHashMap<>(parameters);
   resolved.remove("workspaceName");
   resolved.put("orgId","org-1");
   return new ResolvedTarget(ids.get(0),resolved);
  }
  @Override public Map<String,String> operationMetadata(SubmissionRequest request){
   METADATA.add(request.targetId()+" "+request.parameters());
   String org=request.parameters().get("orgId");
   return org==null?Map.of():Map.of("orgId",org);
  }
  @Override public JobResult submit(SubmissionRequest request,Run<?,?> run){
   REQUESTS.add(request.targetId()+" "+request.parameters());
   // Simulates a controller restart during submission: this thread never returns, so it never touches the
   // execution of the stopped controller; the resumed execution resubmits.
   if(blockSubmit)while(true){try{Thread.sleep(Long.MAX_VALUE);}catch(InterruptedException ignored){}}
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
  @DataBoundConstructor public FakeProvisionStep(String connection,String workspaceId){super(connection,workspaceId);}
  public String getConnection(){return getConnectionId();}
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

 /** A provider step that accepts either an ID or a name: the ID is a setter since Declarative requires constructor args. */
 public static final class FakeLookupProvisionStep extends AbstractProvisionStep {
  private String workspaceName,mode="plan";
  @DataBoundConstructor public FakeLookupProvisionStep(String connection){super(connection);}
  public String getConnection(){return getConnectionId();}
  public String getWorkspaceId(){return getTargetId();}
  @DataBoundSetter public void setWorkspaceId(String value){setTargetId(value);}
  public String getWorkspaceName(){return workspaceName;}
  @DataBoundSetter public void setWorkspaceName(String value){workspaceName=Identifiers.optionalOpaque(value,"workspaceName");}
  public String getMode(){return mode;}
  @DataBoundSetter public void setMode(String value){mode=value;}
  @Override protected String provider(){return "fake";}
  @Override protected Map<String,String> parameters(){
   if((getTargetId()==null)==(workspaceName==null))throw new IllegalArgumentException("Set either workspaceId or workspaceName");
   Map<String,String> p=new LinkedHashMap<>();
   if(workspaceName!=null)p.put("workspaceName",workspaceName);
   p.put("mode",mode);
   return p;
  }
  @TestExtension public static final class DescriptorImpl extends StepDescriptor {
   @Override public String getFunctionName(){return "fakeLookupProvision";}
   @Override public boolean takesImplicitBlockArgument(){return true;}
   @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
  }
 }

 public static final class FakeAwaitStep extends AbstractAwaitStep {
  @DataBoundConstructor public FakeAwaitStep(String connection,String operationKey){super(connection,operationKey);}
  public String getConnection(){return getConnectionId();}
  @Override protected String provider(){return "fake";}
  @TestExtension public static final class DescriptorImpl extends StepDescriptor {
   @Override public String getFunctionName(){return "fakeAwait";}
   @Override public boolean takesImplicitBlockArgument(){return true;}
   @Override public Set<? extends Class<?>> getRequiredContext(){return Set.of(Run.class,TaskListener.class);}
  }
 }
}
