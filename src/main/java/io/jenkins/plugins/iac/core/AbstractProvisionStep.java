package io.jenkins.plugins.iac.core;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundSetter;
import java.util.Map;
/** Parent for provider-specific Declarative stage wrapper options, not user-facing generic DSL. */
public abstract class AbstractProvisionStep extends Step {
 private final String connectionId,targetId;
 private String operationKey;
 private boolean waitForCompletion=true;
 private int pollingSeconds=10,timeoutMinutes=60;
 protected AbstractProvisionStep(String connectionId,String targetId){
  this.connectionId=Identifiers.required(connectionId,"connectionId");
  this.targetId=Identifiers.opaque(targetId,"targetId");
 }
 public String getConnectionId(){return connectionId;}
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
 protected abstract Map<String,String> parameters();
 @Override public final StepExecution start(StepContext context){
  String key=operationKey==null?Identifiers.defaultOperationKey(provider(),targetId):operationKey;
  return new RemoteExecution(context,provider(),connectionId,targetId,key,
    parameters(),waitForCompletion,pollingSeconds,timeoutMinutes,false);
 }
}
