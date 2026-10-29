package io.jenkins.plugins.iac.core;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.steps.*;
import java.io.IOException;
import java.util.LinkedHashMap;
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
 /** Consecutive transient status-poll failures tolerated before the step fails. */
 static final int MAX_TRANSIENT_STATUS_FAILURES=5;
 private final String provider,connectionId,targetId,key;
 private final Map<String,String> parameters;
 private final boolean waitForCompletion,awaitOnly;
 private final int pollingSeconds,timeoutMinutes;
 private volatile boolean bodyStarted;
 private volatile boolean stopped;
 private long waitStartedAt;
 private int transientStatusFailures;
 /** Identifies the operation this (non-await) execution submits; persisted with the execution. */
 private String requestToken;
 /** {@link IacBackend#resolve} result, persisted with the token; null in executions saved before resolution existed. */
 private String resolvedTargetId;
 private LinkedHashMap<String,String> resolvedParameters;
 private volatile BodyExecution body;
 private transient volatile Throwable stopCause;
 private transient ScheduledFuture<?> scheduled;
 public RemoteExecution(StepContext context,String provider,String connectionId,String targetId,String key,
  Map<String,String> parameters,boolean wait,int poll,int timeout,boolean awaitOnly){
  super(context);this.provider=provider;this.connectionId=connectionId;this.targetId=targetId;this.key=key;
  this.parameters=parameters==null?new LinkedHashMap<>():new LinkedHashMap<>(parameters);
  waitForCompletion=wait;pollingSeconds=poll;timeoutMinutes=timeout;this.awaitOnly=awaitOnly;
 }
 @Override public boolean start(){
  if(!awaitOnly)requestToken=UUID.randomUUID().toString();
  waitStartedAt=System.currentTimeMillis();schedule(0);return false;
 }
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
    // Executions persisted before request tokens were kept on the execution resume with none.
    String token=requestToken==null?UUID.randomUUID().toString():requestToken;
    // Lookups are reads: a restart before the save below simply resolves again.
    if(resolvedTargetId==null)resolve(backend,run);
    // Persist the token and resolved target with the execution before they become visible in the build's
    // operation store. A restart before this point resubmits nothing (no stored operation); after it, the
    // resumed execution recognizes the stored operation as its own. With a durability hint that skips per-step
    // saves, saveState() is a no-op, and only a clean shutdown (which saves the program) keeps the build
    // resumable anyway.
    getContext().saveState().get(1,TimeUnit.MINUTES);
    SubmissionRequest request=new SubmissionRequest(connectionId,resolvedTargetId,token,resolvedParameters);
    store.begin(key,provider,connectionId,resolvedTargetId,token,backend.operationMetadata(request));
    log("submitting target "+resolvedTargetId+" with operationKey "+key);
    JobResult created=backend.submit(request,run);
    acceptCreated(store,created);
    return;
   }else if(requestToken!=null&&!requestToken.equals(operation.requestToken())){
    // Another provision step of this build (or an earlier attempt of this one, e.g. inside retry) owns the key.
    throw new IllegalStateException(duplicateKeyMessage(key));
   }
   if(operation.remoteId()==null){
    if(awaitOnly)throw new IllegalStateException("Remote submission has no saved remote ID for "+key);
    if(!backend.supportsIdempotentSubmit())
      throw new IllegalStateException("Submission was interrupted before remote ID was saved for "+key+
       ". Reconcile manually with the provider before retrying.");
    log("retrying interrupted submission with persisted request token for "+key);
    SubmissionRequest request=new SubmissionRequest(operation.connectionId(),operation.targetId(),operation.requestToken(),
     resolvedParameters==null?parameters:resolvedParameters);
    JobResult created=backend.submit(request,run);
    acceptCreated(store,created);
    return;
   }
   if(!waitForCompletion&&!awaitOnly){startBody();return;}
   if(System.currentTimeMillis()-(waitStartedAt == 0 ? operation.startedAt():waitStartedAt)>TimeUnit.MINUTES.toMillis(timeoutMinutes))
     throw new IOException("Remote operation wait timed out for "+key+" (remote execution may continue)");
   JobResult result;
   try{result=backend.status(operation,run);}
   catch(Exception e){
    if(!retryStatus(backend,e))throw e;
    log("transient error polling ID "+operation.remoteId()+" (attempt "+transientStatusFailures+"/"+
     MAX_TRANSIENT_STATUS_FAILURES+"), retrying in "+pollingSeconds+"s: "+describe(e));
    schedule(pollingSeconds);return;
   }
   statusSucceeded();
   store.status(key,result.status());
   if(result.completed()){
    if(!result.successful())throw new IOException("Remote operation "+operation.remoteId()+" failed: "+result.status());
    log("completed ID "+operation.remoteId()+" with status "+result.status());startBody();return;
   }
   if(result.needsApproval())log("approval pending for ID "+operation.remoteId()+"; approve in provider UI");
   schedule(pollingSeconds);
  }catch(Throwable e){getContext().onFailure(e);}
 }
 private void resolve(IacBackend backend,Run<?,?> run) throws Exception {
  ResolvedTarget resolved=backend.resolve(connectionId,targetId,parameters,run);
  if(resolved==null)throw new IllegalStateException("Provider "+provider+" resolved no target");
  if(!resolved.targetId().equals(targetId))log("resolved target to "+resolved.targetId());
  // Parameters are non-secret by contract; log the IDs a lookup added or changed.
  for(Map.Entry<String,String> e:resolved.parameters().entrySet())
   if(e.getValue()!=null&&!e.getValue().equals(parameters.get(e.getKey())))log("resolved "+e.getKey()+" to "+e.getValue());
  resolvedParameters=new LinkedHashMap<>(resolved.parameters());
  resolvedTargetId=resolved.targetId();
 }
 static String duplicateKeyMessage(String key){
  return "operationKey '"+key+"' is already used by another provision step in this build. "+
   "Set a distinct operationKey in the stage options (a provision step re-run in the same build, e.g. inside retry, also needs its own key).";
 }
 /** Counts a failed status poll; true if it is transient and the consecutive-failure budget is not exhausted. */
 boolean retryStatus(IacBackend backend,Exception e){
  if(e instanceof InterruptedException || !backend.isTransient(e))return false;
  return ++transientStatusFailures<=MAX_TRANSIENT_STATUS_FAILURES;
 }
 void statusSucceeded(){transientStatusFailures=0;}
 private static String describe(Exception e){
  return e.getMessage()==null?e.getClass().getSimpleName():e.getClass().getSimpleName()+": "+e.getMessage();
 }
 private void startBody() throws Exception {
  if(stopped || bodyStarted) return;
  bodyStarted=true;
  getContext().saveState();
  BodyExecution started=getContext().newBodyInvoker().withCallback(BodyExecutionCallback.wrap(getContext())).start();
  body=started;
  // stop() may have run between the stopped check and the assignment above, seeing no body yet.
  if(stopped)started.cancel(stopCause);
 }
 @Override public void stop(Throwable cause){
  stopCause=cause;
  stopped=true;
  if(scheduled!=null)scheduled.cancel(false);
  // We do not silently cancel remote jobs on Jenkins abort; remote cancel must be explicit.
  BodyExecution running=body;
  // The body callback completes the step context once the cancelled body unwinds.
  if(running!=null)running.cancel(cause);
  else getContext().onFailure(cause);
 }
}
