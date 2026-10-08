package io.jenkins.plugins.iac.core;

import hudson.model.InvisibleAction;
import hudson.model.Run;
import jenkins.model.RunAction2;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Build-scoped durable correlation. Provider metadata stored here must never contain secrets. */
public final class OperationStoreAction extends InvisibleAction implements RunAction2 {
 private transient Run<?,?> owner;
 private LinkedHashMap<String,RemoteOperation> operations=new LinkedHashMap<>();

 @Override public synchronized void onAttached(Run<?,?> r){owner=r;}
 @Override public synchronized void onLoad(Run<?,?> r){owner=r;}

 public synchronized RemoteOperation get(String key){return operations.get(key);}

 public synchronized void begin(String key,String provider,String connectionId,String targetId,String requestToken,
  Map<String,String> metadata) throws IOException {
  Identifiers.required(key,"operationKey");
  if(operations.containsKey(key)) throw new IllegalStateException("operationKey already exists in build: "+key);
  operations.put(key,new RemoteOperation(key,provider,connectionId,targetId,requestToken,null,"submitting",metadata,System.currentTimeMillis()));
  owner.save();
 }

 public synchronized void accepted(String key,String remoteId,String status) throws IOException {
  RemoteOperation x=required(key);
  if(x.remoteId()!=null) throw new IllegalStateException("Operation already submitted: "+key);
  operations.put(key,x.accepted(remoteId,status));
  owner.save();
 }

 public synchronized void status(String key,String value) throws IOException {
  RemoteOperation x=required(key);
  operations.put(key,x.withStatus(value));
  owner.save();
 }

 private RemoteOperation required(String key){
  RemoteOperation x=operations.get(key);
  if(x==null)throw new IllegalArgumentException("Unknown operationKey: "+key);
  return x;
 }

 @SuppressWarnings("unused")
 private Object readResolve(){
  if(operations==null)operations=new LinkedHashMap<>();
  return this;
 }

 public static synchronized OperationStoreAction forBuild(Run<?,?> run){
  OperationStoreAction action=run.getAction(OperationStoreAction.class);
  if(action==null){
   action=new OperationStoreAction();
   run.addAction(action);
   action.onAttached(run);
  }
  return action;
 }
}
