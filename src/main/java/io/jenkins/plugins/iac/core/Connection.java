package io.jenkins.plugins.iac.core;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.Serializable;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;
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
 @Override public Descriptor<Connection> getDescriptor(){return Jenkins.get().getDescriptorByType(DescriptorImpl.class);}
 @Extension public static final class DescriptorImpl extends Descriptor<Connection>{
    @Override public String getDisplayName(){return "IaC API connection";}
    /** Global connections are admin-only; others only see the value already saved. */
    @POST public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId){
     StandardListBoxModel model=new StandardListBoxModel();
     if(!Jenkins.get().hasPermission(Jenkins.ADMINISTER))return model.includeCurrentValue(credentialsId);
     return model.includeEmptyValue()
       .includeMatchingAs(ACL.SYSTEM2,Jenkins.get(),StringCredentials.class,List.of(),CredentialsMatchers.always())
       .includeCurrentValue(credentialsId);
    }
    @POST public FormValidation doCheckUrl(@QueryParameter String value,@QueryParameter boolean allowHttp){
     if(!Jenkins.get().hasPermission(Jenkins.ADMINISTER))return FormValidation.ok();
     try{HttpJsonClient.validateUrl(value,allowHttp);return FormValidation.ok();}
     catch(IllegalArgumentException e){return FormValidation.error(e.getMessage());}
    }
 }
}
