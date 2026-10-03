package io.jenkins.plugins.iac.core;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import java.io.Serializable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
public final class Connection implements Describable<Connection>, Serializable {
 private static final long serialVersionUID=1L;
 private final String name, url, credentialsId;
 private boolean allowHttp;
 @DataBoundConstructor public Connection(String name,String url,String credentialsId){
   this.name=Identifiers.required(name,"server");this.url=url;this.credentialsId=Identifiers.required(credentialsId,"credentialsId");
 }
 public String getName(){return name;}
 public String getUrl(){return url;}
 public String getCredentialsId(){return credentialsId;}
 public boolean isAllowHttp(){return allowHttp;}
 @DataBoundSetter public void setAllowHttp(boolean value){allowHttp=value;}
 @Override public Descriptor<Connection> getDescriptor(){return jenkins.model.Jenkins.get().getDescriptorByType(DescriptorImpl.class);}
 @Extension public static final class DescriptorImpl extends Descriptor<Connection>{
    @Override public String getDisplayName(){return "IaC API connection";}
 }
}
