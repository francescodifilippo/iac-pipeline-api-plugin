package io.jenkins.plugins.iac.core;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.steps.*;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
/**
 * CPS-safe asynchronous block step: no Jenkins executor held while waiting.
 * Stores remote identity in a build action before proceeding. Providers may opt in
 * to safe resubmission after a restart by honoring the persisted request token.
 */
public final class RemoteExecution extends StepExecution {
 private static final long serialVersionUID=1L;
 private final String provider,connectionId,targetId,key;
 private final Map<String,String> parameters;
 private final boolean waitForCompletion,awaitOnly;
 private final int pollingSeconds,timeoutMinutes;
 private volatile boolean bodyStarted;
 private volatile boolean stopped;
 private long waitStartedAt;
 private transient ScheduledFuture<?> scheduled;
 public RemoteExecution(StepContext context,String provider,String connectionId,String targetId,String key,
  Map<String,String> parameters,boolean wait,int poll,int timeout,boolean awaitOnly){
  super(context);this.provider=provider;this.connectionId=connectionId;this.targetId=targetId;this.key=key;
  this.parameters=Map.copyOf(parameters);waitForCompletion=wait;pollingSeconds=poll;timeoutMinutes=timeout;this.awaitOnly=awaitOnly;
 }
 @Override public boolean start(){waitStartedAt=System.currentTimeMillis();schedule(0);return false;}
 @Override public void onResume(){if(!bodyStarted)schedule(0);}
 private void schedule(long seconds){scheduled=Timer.get().schedule(this::advance,seconds,TimeUnit.SECONDS);}
 private void log(String m) throws IOException,InterruptedException {
  getContext().get(TaskListener.class).getLogger().println("IaC "+provider+": "+m);
 }
 private void acceptCreated(OperationStoreAction store,JobResult created) throws Exception {
  store.accepted(key,created.id(),created.status());
  getContext().saveState();
  log("accepted remote ID "+created.id()+" status "+created.status());
  if(created.completed()){
   if(!created.successful())throw new IOException("Remote operation failed: "+created.status());
   startBody();return;
  }
  if(!waitForCompletion){startBody();return;}
  schedule(pollingSeconds);
 }
 private void advance(){
  if(bodyStarted || stopped)return;
  try{
   Run<?,?> run=getContext().get(Run.class);
   IacBackend backend=IacBackend.find(provider);
   OperationStoreAction store=OperationStoreAction.forBuild(run);
   RemoteOperation operation=store.get(key);
   if(awaitOnly){
    if(operation==null)throw new IllegalStateException("No remote operation found in this build: "+key);
    if(!provider.equals(operation.provider())||!connectionId.equals(operation.connectionId()))
      throw new IllegalStateException("Wait provider/connection differs from original submission for "+key);
   }else if(operation==null){
    String requestToken=UUID.randomUUID().toString();
    SubmissionRequest request=new SubmissionRequest(connectionId,targetId,requestToken,parameters);
    store.begin(key,provider,connectionId,targetId,requestToken,backend.operationMetadata(request));
    log("submitting target "+targetId+" with operationKey "+key);
    JobResult created=backend.submit(request,run);
    acceptCreated(store,created);
    return;
   }
   if(operation.remoteId()==null){
    if(awaitOnly)throw new IllegalStateException("Remote submission has no saved remote ID for "+key);
    if(!backend.supportsIdempotentSubmit())
      throw new IllegalStateException("Submission was interrupted before remote ID was saved for "+key+
       ". Reconcile manually with the provider before retrying.");
    log("retrying interrupted submission with persisted request token for "+key);
    SubmissionRequest request=new SubmissionRequest(operation.connectionId(),operation.targetId(),operation.requestToken(),parameters);
    JobResult created=backend.submit(request,run);
    acceptCreated(store,created);
    return;
   }
   if(!waitForCompletion&&!awaitOnly){startBody();return;}
   if(System.currentTimeMillis()-(waitStartedAt == 0 ? operation.startedAt():waitStartedAt)>TimeUnit.MINUTES.toMillis(timeoutMinutes))
     throw new IOException("Remote operation wait timed out for "+key+" (remote execution may continue)");
   JobResult result=backend.status(operation,run);
   store.status(key,result.status());
   if(result.completed()){
    if(!result.successful())throw new IOException("Remote operation "+operation.remoteId()+" failed: "+result.status());
    log("completed ID "+operation.remoteId()+" with status "+result.status());startBody();return;
   }
   if(result.needsApproval())log("approval pending for ID "+operation.remoteId()+"; approve in provider UI");
   schedule(pollingSeconds);
  }catch(Throwable e){getContext().onFailure(e);}
 }
 private void startBody() throws Exception {
  if(stopped || bodyStarted) return;
  bodyStarted=true;
  getContext().saveState();
  getContext().newBodyInvoker().withCallback(BodyExecutionCallback.wrap(getContext())).start();
 }
 @Override public void stop(Throwable cause){
  stopped=true;
  if(scheduled!=null)scheduled.cancel(false);
  // We do not silently cancel remote jobs on Jenkins abort; remote cancel must be explicit.
  getContext().onFailure(cause);
 }
}
