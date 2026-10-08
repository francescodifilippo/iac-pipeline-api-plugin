package io.jenkins.plugins.iac.core;

import static org.junit.jupiter.api.Assertions.*;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class OperationStorePersistenceTest {
 @RegisterExtension
 private final JenkinsSessionExtension sessions=new JenkinsSessionExtension();

 @Test
 void persistsAcceptedOperationAcrossControllerRestart() throws Throwable {
  sessions.then(j -> {
   FreeStyleProject p=j.createFreeStyleProject("persisted-operation");
   FreeStyleBuild b=j.buildAndAssertSuccess(p);
   OperationStoreAction store=OperationStoreAction.forBuild(b);
   store.begin("plan","oci-rm","oci-prod","ocid1.ormstack.oc1..example","request-token",
     Map.of("mode","plan"));
   store.accepted("plan","ocid1.ormjob.oc1..example","IN_PROGRESS");
  });

  sessions.then(j -> {
   FreeStyleProject p=j.jenkins.getItemByFullName("persisted-operation",FreeStyleProject.class);
   assertNotNull(p);
   FreeStyleBuild b=p.getBuildByNumber(1);
   assertNotNull(b);
   OperationStoreAction store=b.getAction(OperationStoreAction.class);
   assertNotNull(store);
   RemoteOperation op=store.get("plan");
   assertNotNull(op);
   assertEquals("oci-rm",op.provider());
   assertEquals("oci-prod",op.connectionId());
   assertEquals("ocid1.ormstack.oc1..example",op.targetId());
   assertEquals("request-token",op.requestToken());
   assertEquals("ocid1.ormjob.oc1..example",op.remoteId());
   assertEquals("IN_PROGRESS",op.status());
   assertEquals("plan",op.metadata().get("mode"));
   store.status("plan","SUCCEEDED");
  });

  sessions.then(j -> {
   FreeStyleProject p=j.jenkins.getItemByFullName("persisted-operation",FreeStyleProject.class);
   assertNotNull(p);
   FreeStyleBuild b=p.getBuildByNumber(1);
   assertNotNull(b);
   RemoteOperation op=b.getAction(OperationStoreAction.class).get("plan");
   assertEquals("SUCCEEDED",op.status());
  });
 }

 @Test
 void persistsRequestTokenBeforeRemoteIdIsKnown() throws Throwable {
  sessions.then(j -> {
   FreeStyleProject p=j.createFreeStyleProject("interrupted-submit");
   FreeStyleBuild b=j.buildAndAssertSuccess(p);
   OperationStoreAction.forBuild(b).begin(
     "apply","oci-rm","oci-prod","ocid1.ormstack.oc1..example","stable-token",Map.of("mode","apply"));
  });

  sessions.then(j -> {
   FreeStyleProject p=j.jenkins.getItemByFullName("interrupted-submit",FreeStyleProject.class);
   assertNotNull(p);
   FreeStyleBuild b=p.getBuildByNumber(1);
   assertNotNull(b);
   RemoteOperation op=b.getAction(OperationStoreAction.class).get("apply");
   assertEquals("stable-token",op.requestToken());
   assertNull(op.remoteId());
   assertEquals("submitting",op.status());
  });
 }
}
