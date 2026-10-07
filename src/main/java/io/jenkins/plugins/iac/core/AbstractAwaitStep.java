package io.jenkins.plugins.iac.core;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.DataBoundSetter;
import java.util.Map;
/** Wait stage wrapper that resolves the build's previously submitted remote operation. */
public abstract class AbstractAwaitStep extends Step {
 private final String connectionId,operationKey;
 private int pollingSeconds=10,timeoutMinutes=60;
 protected AbstractAwaitStep(String connectionId,String operationKey){
  this.connectionId=Identifiers.required(connectionId,"connectionId");
  this.operationKey=Identifiers.required(operationKey,"operationKey");
 }
 public String getConnectionId(){return connectionId;}
 public String getOperationKey(){return operationKey;}
 public int getPollingSeconds(){return pollingSeconds;}
 public int getTimeoutMinutes(){return timeoutMinutes;}
 @DataBoundSetter public void setPollingSeconds(int value){if(value<5||value>300)throw new IllegalArgumentException("pollingSeconds must be 5..300");pollingSeconds=value;}
 @DataBoundSetter public void setTimeoutMinutes(int value){if(value<1||value>1440)throw new IllegalArgumentException("timeoutMinutes must be 1..1440");timeoutMinutes=value;}
 protected abstract String provider();
 @Override public final StepExecution start(StepContext context){
  return new RemoteExecution(context,provider(),connectionId,null,operationKey,Map.of(),true,pollingSeconds,timeoutMinutes,true);
 }
}
