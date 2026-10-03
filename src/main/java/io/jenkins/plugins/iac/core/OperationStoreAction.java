package io.jenkins.plugins.iac.core;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import jenkins.model.RunAction2;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
/** Build-scoped durable correlation: secrets NEVER stored here. */
public final class OperationStoreAction extends InvisibleAction implements RunAction2 {
 private transient Run<?,?> owner;
 private Map<String,Operation> operations=new LinkedHashMap<>();
 @Override public void onAttached(Run<?,?> r){owner=r;}
 @Override public void onLoad(Run<?,?> r){owner=r;}
 public synchronized Operation get(String key){return operations.get(key);}
 public synchronized void begin(String key,String provider,String server,String organizationId) throws IOException {
  Identifiers.required(key,"operationKey");
  if(operations.containsKey(key)) throw new IllegalStateException("operationKey already exists in build: "+key);
  operations.put(key,new Operation(key,provider,server,organizationId,null,"submitting",System.currentTimeMillis()));
  owner.save();
 }
 public synchronized void accepted(String key,String id,String status) throws IOException {
  Operation x=required(key);
  if(x.id!=null) throw new IllegalStateException("Operation already submitted: "+key);
  operations.put(key,new Operation(key,x.provider,x.server,x.organizationId,Identifiers.required(id,"remoteId"),status,x.startedAt));
  owner.save();
 }
 public synchronized void status(String key,String value) throws IOException {
  Operation x=required(key);
  operations.put(key,new Operation(key,x.provider,x.server,x.organizationId,x.id,value,x.startedAt));owner.save();
 }
 private Operation required(String key){Operation x=operations.get(key);if(x==null)throw new IllegalArgumentException("Unknown operationKey: "+key);return x;}
 public static synchronized OperationStoreAction forBuild(Run<?,?> run){
  OperationStoreAction action=run.getAction(OperationStoreAction.class);
  if(action==null){action=new OperationStoreAction();run.addAction(action);action.onAttached(run);}
  return action;
 }
 public record Operation(String key,String provider,String server,String organizationId,String id,String status,long startedAt) implements Serializable {
  private static final long serialVersionUID=1L;
 }
}
