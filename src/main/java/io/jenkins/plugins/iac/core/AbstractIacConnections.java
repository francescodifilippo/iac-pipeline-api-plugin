package io.jenkins.plugins.iac.core;
import hudson.model.Run;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import hudson.model.Descriptor.FormException;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest2;
import java.util.*;
/**
 * Optional HTTP bearer-token connection helper for provider plugins.
 * IacBackend does not require this transport model; non-HTTP providers may own a different configuration.
 */
public abstract class AbstractIacConnections extends GlobalConfiguration {
 private List<Connection> servers = new ArrayList<>();
 protected AbstractIacConnections(){load();}
 public List<Connection> getServers(){return servers == null?List.of():List.copyOf(servers);}
 @DataBoundSetter public void setServers(List<Connection> input){
  jenkins.model.Jenkins.get().checkPermission(jenkins.model.Jenkins.ADMINISTER);
  List<Connection> checked=input==null?List.of():List.copyOf(input);
  Set<String> names=new HashSet<>();
  for(Connection c:checked){
   if(!names.add(c.getName())) throw new IllegalArgumentException("Duplicate IaC connection: "+c.getName());
   HttpJsonClient.validateUrl(c.getUrl(),c.isAllowHttp());
  }
  servers=new ArrayList<>(checked);save();
 }
 /** A repeatable list with every entry deleted submits no "servers" key at all, so binding alone would keep the old list. */
 @Override public boolean configure(StaplerRequest2 req,JSONObject json) throws FormException {
  boolean noServers=!json.has("servers");
  req.bindJSON(this,json);
  if(noServers)setServers(List.of());
  return true;
 }
 public Connection requireServer(String name){return getServers().stream().filter(s->s.getName().equals(name))
   .findFirst().orElseThrow(()->new IllegalArgumentException("Unknown IaC connection: "+name));}
 public HttpJsonClient client(String server, Run<?,?> run){
   Connection c=requireServer(server);
   StringCredentials token=CredentialsProvider.findCredentialById(c.getCredentialsId(),StringCredentials.class,run,Collections.emptyList());
   if(token==null) throw new IllegalArgumentException("Missing or inaccessible credential for server: "+server);
   return new HttpJsonClient(c.getUrl(),token.getSecret().getPlainText(),c.isAllowHttp());
 }
}
