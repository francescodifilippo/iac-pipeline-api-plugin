package io.jenkins.plugins.iac.core;
import java.io.Serializable;
/** Normalized immutable provider response, with no raw headers or credentials. */
public record JobResult(String id,String status,boolean completed,boolean successful,boolean needsApproval) implements Serializable {
 private static final long serialVersionUID=1L;
}
