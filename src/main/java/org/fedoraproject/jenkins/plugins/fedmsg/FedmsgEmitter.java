package org.fedoraproject.jenkins.plugins.fedmsg;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.tasks.Notifier;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.File;
import java.util.HashMap;

import org.fedoraproject.fedmsg.*;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SignatureException;

/**
 * Send a message to the Fedmsg bus when a build is completed.
 *
 * @author Ricky Elrod
 */
public class FedmsgEmitter extends Notifier {

    @DataBoundConstructor
    public FedmsgEmitter() { }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        String endpoint = getDescriptor().getUseStaging() ?
            "tcp://hub.stg.fedoraproject.org:9940"
            : "tcp://hub.fedoraproject.org:9940";

        String environment = getDescriptor().getUseStaging() ? "stg" : "prod";

        FedmsgConnection fedmsg = new FedmsgConnection()
            .setEndpoint(endpoint)
            .setLinger(2000);

        Result buildResult = build.getResult();
        if (buildResult != null) {
            HashMap<String, Object> message = new HashMap();
            message.put("project", build.getProject().getName());
            message.put("build", build.getNumber());

            FedmsgMessage blob = new FedmsgMessage()
                .setTopic("org.fedoraproject." + environment + ".jenkins.build." + buildResult.toString())
                .setI(1)
                .setTimestamp(new java.util.Date())
                .setMessage(message);

            try {
                SignedFedmsgMessage signed = blob.sign(
                    new File("/etc/pki/fedmsg/jenkins-jenkins.cloud.fedoraproject.org.crt"),
                    new File("/etc/pki/fedmsg/jenkins-jenkins.cloud.fedoraproject.org.key"));
                fedmsg.send(signed);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        /**
         * To persist global configuration information,
         * simply store it in a field and call save().
         *
         * <p>
         * If you don't want fields to be persisted, use <tt>transient</tt>.
         */
        private boolean useStaging;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Send messages to Fedmsg";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            useStaging = formData.getBoolean("useStaging");
            save();
            return super.configure(req, formData);
        }

        /**
         * This method returns true if the global configuration says we should use staging.
         */
        public boolean getUseStaging() {
            return useStaging;
        }
    }
}

