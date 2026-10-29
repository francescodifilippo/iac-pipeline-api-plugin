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
 /**
  * Turns the provision step's target into provider IDs, once per provision step before anything is submitted
  * or stored (never for await steps). {@code targetId} is the step's configured ID, or null when the step names
  * its target in {@code parameters}. Look names up here, requiring exactly one match: fail with a clear
  * exception for none or several, never pick one. The returned parameters replace the step's: drop name
  * parameters or add resolved IDs as needed, since {@link #operationMetadata}, {@link #submit} and the stored
  * {@link RemoteOperation} only see the result. Lookups must be read-only, as they may be repeated after a
  * restart. A failure fails the step and is not retried.
  * Default: accepts a non-null targetId and the parameters unchanged.
  */
 default ResolvedTarget resolve(String connectionId,String targetId,Map<String,String> parameters,Run<?,?> run) throws Exception {
  if(targetId==null)throw new IllegalArgumentException(id()+" target id is required");
  return new ResolvedTarget(targetId,parameters);
 }
 /** Provider-selected, non-secret values needed to resume/status an operation. */
 default Map<String,String> operationMetadata(SubmissionRequest request){return Map.of();}
 /**
  * Whether a {@link #status} failure may succeed on a later poll. Never consulted for {@link #submit}.
  * Default: any IOException except an HTTP 4xx response, though 408 and 429 are transient.
  * Providers whose client throws other exception types (for example SDK runtime exceptions) should override.
  */
 default boolean isTransient(Exception e){
  if(e instanceof IacHttpException http){
   int code=http.statusCode();
   return code<400||code>=500||code==408||code==429;
  }
  return e instanceof java.io.IOException;
 }
 static IacBackend find(String id) {
   return ExtensionList.lookup(IacBackend.class).stream().filter(x->x.id().equals(id)).findFirst()
      .orElseThrow(()->new IllegalArgumentException("Provider plugin not installed: "+id));
 }
}
