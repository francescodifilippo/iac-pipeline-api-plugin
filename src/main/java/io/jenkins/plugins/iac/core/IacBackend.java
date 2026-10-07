package io.jenkins.plugins.iac.core;
import hudson.ExtensionPoint;
import hudson.ExtensionList;
import hudson.model.Run;
import java.util.Map;
public interface IacBackend extends ExtensionPoint {
 String id();
 JobResult submit(SubmissionRequest request,Run<?,?> run) throws Exception;
 JobResult status(RemoteOperation operation,Run<?,?> run) throws Exception;
 default boolean supportsIdempotentSubmit(){return false;}
 /** Provider-selected, non-secret values needed to resume/status an operation. */
 default Map<String,String> operationMetadata(SubmissionRequest request){return Map.of();}
 static IacBackend find(String id) {
   return ExtensionList.lookup(IacBackend.class).stream().filter(x->x.id().equals(id)).findFirst()
      .orElseThrow(()->new IllegalArgumentException("Provider plugin not installed: "+id));
 }
}
