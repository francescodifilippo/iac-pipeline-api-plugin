package io.jenkins.plugins.iac.core;
import java.io.IOException;
/** Non-2xx IaC API response. The message carries only status, method and path: never bodies or tokens. */
public final class IacHttpException extends IOException {
 private static final long serialVersionUID=1L;
 private final int statusCode;
 public IacHttpException(int statusCode,String method,String path){
  super("IaC API HTTP "+statusCode+" for "+method+" "+path);
  this.statusCode=statusCode;
 }
 public int statusCode(){return statusCode;}
}
