package io.jenkins.plugins.iac.core;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.steps.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
/**
 * CPS-safe asynchronous block step: no Jenkins executor held while waiting.
 * Stores remote identity in build action before proceeding. On restart an ambiguous
 * submission is rejected instead of submitting a second destructive apply.
 */
public final class RemoteExecution extends StepExecution {
 private static final long serialVersionUID=1L;
 private final String provider,server,workspaceId,key;
 private final Map<String,String> options;
 private final boolean waitForCompletion,awaitOnly;
 private final int pollingSeconds,timeoutMinutes;
 private volatile boolean bodyStarted;
 private volatile boolean stopped;
 private long waitStartedAt;
 private transient ScheduledFuture<?> scheduled;
 public RemoteExecution(StepContext context,String provider,String server,String workspaceId,String key,
  Map<String,String> options,boolean wait,int poll,int timeout,boolean awaitOnly){
  super(context);this.provider=provider;this.server=server;this.workspaceId=workspaceId;this.key=key;
  this.options=Map.copyOf(options);waitForCompletion=wait;pollingSeconds=poll;timeoutMinutes=timeout;this.awaitOnly=awaitOnly;
 }
 @Override public boolean start(){waitStartedAt=System.currentTimeMillis();schedule(0);return false;}
 @Override public void onResume(){if(!bodyStarted)schedule(0);}
 private void schedule(long seconds){scheduled=Timer.get().schedule(this::advance,seconds,TimeUnit.SECONDS);}
 private void log(String m) throws IOException,InterruptedException {
  getContext().get(TaskListener.class).getLogger().println("IaC "+provider+": "+m);
 }
 private void advance(){
  if(bodyStarted || stopped)return;
  try{
   Run<?,?> run=getContext().get(Run.class);
   IacBackend backend=IacBackend.find(provider);
   OperationStoreAction store=OperationStoreAction.forBuild(run);
   OperationStoreAction.Operation operation=store.get(key);
   if(awaitOnly){
    if(operation==null)throw new IllegalStateException("No remote operation found in this build: "+key);
    if(!provider.equals(operation.provider())||!server.equals(operation.server()))
      throw new IllegalStateException("Wait provider/server differs from original submission for "+key);
   }else if(operation==null){
    store.begin(key,provider,server,options.get("organizationId"));
    log("submitting workspace "+workspaceId+" with operationKey "+key);
    JobResult created=backend.submit(server,workspaceId,options,run);
    store.accepted(key,created.id(),created.status());
    getContext().saveState();
    log("accepted remote ID "+created.id()+" status "+created.status());
    if(created.completed()){
      if(!created.successful())throw new IOException("Remote operation failed: "+created.status());
      startBody();return;
    }
    if(!waitForCompletion){startBody();return;}
    schedule(pollingSeconds);return;
   }
   // If a restart happened after BEGIN but before ACCEPT we cannot prove whether
   // the provider created the job. Never silently retry a potentially destructive POST.
   if(operation.id()==null)throw new IllegalStateException("Submission was interrupted before remote ID was saved for "+key+
      ". Reconcile manually with the provider before retrying.");
   if(!waitForCompletion&&!awaitOnly){startBody();return;}
   if(System.currentTimeMillis()-(waitStartedAt == 0 ? operation.startedAt():waitStartedAt)>TimeUnit.MINUTES.toMillis(timeoutMinutes))
     throw new IOException("Remote operation wait timed out for "+key+" (remote execution may continue)");
   JobResult result=backend.status(server,operation.organizationId(),operation.id(),run);
   store.status(key,result.status());
   if(result.completed()){
    if(!result.successful())throw new IOException("Remote operation "+operation.id()+" failed: "+result.status());
    log("completed ID "+operation.id()+" with status "+result.status());startBody();return;
   }
   if(result.needsApproval())log("approval pending for ID "+operation.id()+"; approve in provider UI");
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
