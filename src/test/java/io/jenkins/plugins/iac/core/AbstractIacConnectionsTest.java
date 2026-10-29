package io.jenkins.plugins.iac.core;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.ExtensionList;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AbstractIacConnectionsTest {
 private static final String URL="https://iac.example.org";
 private JenkinsRule j;

 @BeforeEach void setUp(JenkinsRule rule) throws Exception {
  j=rule;
  SystemCredentialsProvider.getInstance().getCredentials().add(
    new StringCredentialsImpl(CredentialsScope.GLOBAL,"iac-token","IaC token",Secret.fromString("not-a-real-token")));
  SystemCredentialsProvider.getInstance().save();
 }

 @Test void roundtripsOneConnectionWithCredentialSelect() throws Exception {
  Connection c=new Connection("prod",URL,"iac-token");
  c.setAllowHttp(false);
  connections().setServers(List.of(c));
  HtmlPage page=j.createWebClient().goTo("configure");
  assertNotNull(page.querySelector("select.credentials-select[name='_.credentialsId']"),"credentials select rendered");
  // The credentialsId select is filled by AJAX; an empty submission would be rejected by Connection.
  j.configRoundtrip();
  List<Connection> servers=connections().getServers();
  assertEquals(1,servers.size());
  assertEquals("prod",servers.get(0).getName());
  assertEquals(URL,servers.get(0).getUrl());
  assertEquals("iac-token",servers.get(0).getCredentialsId());
  assertFalse(servers.get(0).isAllowHttp());
 }

 @Test void credentialItemsRequireAdminister() {
  j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
  j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
    .grant(Jenkins.ADMINISTER).everywhere().to("admin").grant(Jenkins.READ).everywhere().to("reader"));
  Connection.DescriptorImpl d=ExtensionList.lookupSingleton(Connection.DescriptorImpl.class);
  try(ACLContext ctx=ACL.as(User.getById("admin",true))){
   ListBoxModel items=d.doFillCredentialsIdItems("");
   assertTrue(items.stream().anyMatch(o->"iac-token".equals(o.value)),"admin sees credentials");
   assertTrue(d.doCheckUrl("http://iac.example.org",false).kind!=hudson.util.FormValidation.Kind.OK);
   assertEquals(hudson.util.FormValidation.Kind.OK,d.doCheckUrl("http://iac.example.org",true).kind);
  }
  try(ACLContext ctx=ACL.as(User.getById("reader",true))){
   ListBoxModel items=d.doFillCredentialsIdItems("saved-id");
   assertEquals(1,items.size(),"non-admin only sees the current value");
   assertEquals("saved-id",items.get(0).value);
  }
 }

 private static TestConnections connections(){return ExtensionList.lookupSingleton(TestConnections.class);}

 @TestExtension public static final class TestConnections extends AbstractIacConnections {
  @Override public String getDisplayName(){return "Test IaC connections";}
 }
}
