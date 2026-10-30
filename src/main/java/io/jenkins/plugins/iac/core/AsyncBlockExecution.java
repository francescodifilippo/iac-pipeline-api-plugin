package io.jenkins.plugins.iac.core;
import hudson.model.TaskListener;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.steps.*;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
/**
 * Base for provider stage options that do remote work before their body, such as finding or creating a workspace
 * or stack. The work runs off the CPS thread, holding no executor: {@link #advance} runs when the step starts, again
 * after {@link #pollingSeconds} while it returns false, and again after a controller restart until the body has
 * started. The body then runs once.
 * <p>
 * Keep progress in serializable fields and call {@link #save} after changing them and before each remote side
 * effect, so that a resumed execution knows what it already did and repeats only safe calls. An exception from
 * {@link #advance} fails the step. Aborting the build stops polling and the body, never the remote work.
 * <p>
 * {@link RemoteExecution} does not extend this class, so that executions saved by earlier versions still resume.
 */
public abstract class AsyncBlockExecution extends StepExecution {
 private static final long serialVersionUID=1L;
 private final String provider;
 private long startedAt;
 private volatile boolean bodyStarted,stopped;
 private volatile BodyExecution body;
 private transient volatile Throwable stopCause;
 private transient volatile ScheduledFuture<?> scheduled;
 /** @param provider provider ID used as log prefix, for example {@code otf} */
 protected AsyncBlockExecution(StepContext context,String provider){
  super(context);this.provider=Identifiers.required(provider,"provider");
 }
 /**
  * One round of remote work. Runs on a background thread, never concurrently with itself.
  * @return true when the stage body may run, false to run again after {@link #pollingSeconds}
  */
 protected abstract boolean advance() throws Exception;
 /** Seconds between rounds of {@link #advance} that return false. Default 10. */
 protected int pollingSeconds(){return 10;}
 /** When the step started, in milliseconds since the epoch; kept across restarts, for timeouts. */
 protected final long startedAt(){return startedAt;}
 /** Prints {@code "IaC <provider>: "} and the message to the build log. Never log secrets. */
 protected final void log(String message) throws IOException,InterruptedException {
  getContext().get(TaskListener.class).getLogger().println("IaC "+provider+": "+message);
 }
 /** Saves the execution with the build and waits for the save, so that a restart afterwards sees the progress. */
 protected final void save() throws Exception {getContext().saveState().get(1,TimeUnit.MINUTES);}

 @Override public final boolean start(){startedAt=System.currentTimeMillis();schedule(0);return false;}
 @Override public final void onResume(){if(!bodyStarted)schedule(0);}
 private void schedule(long seconds){scheduled=Timer.get().schedule(this::round,seconds,TimeUnit.SECONDS);}
 private void round(){
  if(bodyStarted||stopped)return;
  try{
   if(advance())startBody();
   else if(!stopped)schedule(pollingSeconds());
  }catch(Throwable e){getContext().onFailure(e);}
 }
 private void startBody() throws Exception {
  if(stopped||bodyStarted)return;
  bodyStarted=true;
  getContext().saveState();
  BodyExecution started=getContext().newBodyInvoker().withCallback(BodyExecutionCallback.wrap(getContext())).start();
  body=started;
  // stop() may have run between the stopped check and the assignment above, seeing no body yet.
  if(stopped)started.cancel(stopCause);
 }
 @Override public final void stop(Throwable cause){
  stopCause=cause;
  stopped=true;
  ScheduledFuture<?> next=scheduled;
  if(next!=null)next.cancel(false);
  BodyExecution running=body;
  // The body callback completes the step context once the cancelled body unwinds.
  if(running!=null)running.cancel(cause);
  else getContext().onFailure(cause);
 }
}
