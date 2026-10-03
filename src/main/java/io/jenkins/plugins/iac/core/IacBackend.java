package io.jenkins.plugins.iac.core;
import hudson.ExtensionPoint;
import hudson.ExtensionList;
import hudson.model.Run;
import java.util.Map;
public interface IacBackend extends ExtensionPoint {
 String id();
 AbstractIacConnections connections();
 JobResult submit(String server, String workspaceId, Map<String,String> options, Run<?,?> run) throws Exception;
 JobResult status(String server, String organizationId, String remoteId, Run<?,?> run) throws Exception;
 static IacBackend find(String id) {
   return ExtensionList.lookup(IacBackend.class).stream().filter(x->x.id().equals(id)).findFirst()
      .orElseThrow(()->new IllegalArgumentException("Provider plugin not installed: "+id));
 }
}
