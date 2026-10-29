package io.jenkins.plugins.iac.core;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundSetter;
import java.util.Map;
/**
 * Parent for provider-specific Declarative stage wrapper options, not user-facing generic DSL.
 * The target ID is optional: a step that lets users name the target instead passes the names in
 * {@link #parameters()} and its backend turns them into an ID in {@link IacBackend#resolve}.
 */
public abstract class AbstractProvisionStep extends Step {
 private final String connectionId;
 private String targetId;
 private String operationKey;
 private boolean waitForCompletion=true;
 private int pollingSeconds=10,timeoutMinutes=60;
 /** @param targetId provider target ID, or null/blank when the step names the target in {@link #parameters()} */
 protected AbstractProvisionStep(String connectionId,String targetId){
  this.connectionId=Identifiers.required(connectionId,"connectionId");
  setTargetId(targetId);
 }
 /**
  * For steps that expose the target ID as an optional {@code @DataBoundSetter} (calling {@link #setTargetId}),
  * so that Pipeline Syntax documentation does not list it as required once a name may replace it.
  */
 protected AbstractProvisionStep(String connectionId){this(connectionId,null);}
 /** Validates and stores the target ID; null or blank clears it. Call from the provider's own setter. */
 protected final void setTargetId(String value){targetId=Identifiers.optionalOpaque(value,"targetId");}
 public String getConnectionId(){return connectionId;}
 /** Target ID as configured, or null when the step names the target instead. */
 public String getTargetId(){return targetId;}
 public String getOperationKey(){return operationKey;}
 public boolean isWaitForCompletion(){return waitForCompletion;}
 public int getPollingSeconds(){return pollingSeconds;}
 public int getTimeoutMinutes(){return timeoutMinutes;}
 @DataBoundSetter public void setOperationKey(String value){operationKey=Identifiers.optional(value,"operationKey");}
 @DataBoundSetter public void setWaitForCompletion(boolean value){waitForCompletion=value;}
 @DataBoundSetter public void setPollingSeconds(int value){if(value<5||value>300)throw new IllegalArgumentException("pollingSeconds must be 5..300");pollingSeconds=value;}
 @DataBoundSetter public void setTimeoutMinutes(int value){if(value<1||value>1440)throw new IllegalArgumentException("timeoutMinutes must be 1..1440");timeoutMinutes=value;}
 protected abstract String provider();
 /**
  * Non-secret provider parameters. Validate step-level combinations here (for example "either ID or name"),
  * since it runs when the step starts. With no target ID, include every name that identifies the target:
  * the default operationKey is derived from the provider, target ID and these parameters.
  */
 protected abstract Map<String,String> parameters();
 @Override public final StepExecution start(StepContext context){
  Map<String,String> p=parameters();
  String key=operationKey==null?Identifiers.defaultOperationKey(provider(),targetId,p):operationKey;
  return new RemoteExecution(context,provider(),connectionId,targetId,key,
    p,waitForCompletion,pollingSeconds,timeoutMinutes,false);
 }
}
