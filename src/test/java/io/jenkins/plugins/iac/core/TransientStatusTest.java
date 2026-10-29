package io.jenkins.plugins.iac.core;
import static org.junit.jupiter.api.Assertions.*;
import hudson.model.Run;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.Test;
class TransientStatusTest {
 private static final IacBackend DEFAULT=new IacBackend(){
  @Override public String id(){return "fake";}
  @Override public JobResult submit(SubmissionRequest request,Run<?,?> run){throw new UnsupportedOperationException();}
  @Override public JobResult status(RemoteOperation operation,Run<?,?> run){throw new UnsupportedOperationException();}
 };
 @Test void defaultClassification(){
  for(int code:new int[]{500,502,503,504,408,429})assertTrue(DEFAULT.isTransient(new IacHttpException(code,"GET","/x")),"HTTP "+code);
  for(int code:new int[]{400,401,403,404,409,422})assertFalse(DEFAULT.isTransient(new IacHttpException(code,"GET","/x")),"HTTP "+code);
  assertTrue(DEFAULT.isTransient(new IOException("connection reset")));
  assertTrue(DEFAULT.isTransient(new HttpTimeoutException("timed out")));
  assertFalse(DEFAULT.isTransient(new IllegalStateException("bug")));
  assertFalse(DEFAULT.isTransient(new InterruptedException()));
 }
 @Test void httpExceptionKeepsMessageFormat(){
  IacHttpException e=new IacHttpException(503,"GET","/api/v2/runs/run-1");
  assertEquals(503,e.statusCode());
  assertEquals("IaC API HTTP 503 for GET /api/v2/runs/run-1",e.getMessage());
  assertInstanceOf(IOException.class,e);
 }
 @Test void retryBudgetIsConsecutiveAndResetsOnSuccess(){
  RemoteExecution x=execution();
  IOException unavailable=new IacHttpException(503,"GET","/x");
  for(int i=0;i<RemoteExecution.MAX_TRANSIENT_STATUS_FAILURES;i++)assertTrue(x.retryStatus(DEFAULT,unavailable),"attempt "+i);
  assertFalse(x.retryStatus(DEFAULT,unavailable),"budget exhausted");
  x.statusSucceeded();
  assertTrue(x.retryStatus(DEFAULT,unavailable),"counter reset after a successful poll");
 }
 @Test void permanentFailuresAreNeverRetried(){
  RemoteExecution x=execution();
  assertFalse(x.retryStatus(DEFAULT,new IacHttpException(404,"GET","/x")));
  assertFalse(x.retryStatus(DEFAULT,new InterruptedException()));
  IacBackend never=new IacBackend(){
   @Override public String id(){return "never";}
   @Override public JobResult submit(SubmissionRequest r,Run<?,?> run){throw new UnsupportedOperationException();}
   @Override public JobResult status(RemoteOperation o,Run<?,?> run){throw new UnsupportedOperationException();}
   @Override public boolean isTransient(Exception e){return false;}
  };
  assertFalse(x.retryStatus(never,new IOException("reset")),"provider override wins");
 }
 private static RemoteExecution execution(){
  return new RemoteExecution(null,"fake","conn","target","key",Map.of(),true,10,60,false);
 }
}
