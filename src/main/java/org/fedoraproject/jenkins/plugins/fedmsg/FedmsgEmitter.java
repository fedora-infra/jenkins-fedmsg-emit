package org.fedoraproject.jenkins.plugins.fedmsg;
import hudson.Launcher;
import hudson.Extension;
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

import java.io.File;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.fedoraproject.fedmsg.*;


/**
 * Send a message to the Fedmsg bus when a build is completed.
 *
 * @author Ricky Elrod
 */
public class FedmsgEmitter extends Notifier {


  private static final Logger LOGGER = Logger.getLogger("FedmsgEmitter");

    @DataBoundConstructor
    public FedmsgEmitter() { }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        String endpoint = getDescriptor().getEndpoint();
        String environment = getDescriptor().getEnvironmentShortname();
        String cert = getDescriptor().getCertificateFile();
        String key  = getDescriptor().getKeystoreFile();
        
        LOGGER.log(Level.SEVERE, "Endpoint: " + endpoint);
        LOGGER.log(Level.SEVERE, "Env: " + environment);
        LOGGER.log(Level.SEVERE, "Certificate: " + cert);
        LOGGER.log(Level.SEVERE, "Keystore: " + key);
        
        FedmsgConnection fedmsg = new FedmsgConnection()
            .setEndpoint(endpoint)
            .setLinger(2000)
            .connect();

        Result buildResult = build.getResult();
        if (buildResult != null) {
            HashMap<String, Object> message = new HashMap();
            message.put("project", build.getProject().getName());
            message.put("build", build.getNumber());

            String status = "";
            if (buildResult.toString().equals("SUCCESS")) {
                status = "passed";
            } else if (buildResult.toString().equals("FAILURE")) {
                status = "failed";
            } else {
                status = "unknown";
            }

            FedmsgMessage blob = new FedmsgMessage()
                .setTopic("org.fedoraproject." + environment + ".jenkins.build." + status)
                .setI(1)
                .setTimestamp(new java.util.Date())
                .setMessage(message);

            try {
                LOGGER.log(Level.SEVERE, "MSG: " + blob.toJson().toString());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error converting (unsigned) message to JSON.");
            }

            try {
                if (getDescriptor().getShouldSign()) {
                    SignedFedmsgMessage signed = blob.sign(
                        new File(cert),
                        new File(key));
                    fedmsg.send(signed);
                } else {
                    fedmsg.send(blob);
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unable to send to fedmsg.", e);
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
        private boolean shouldSign;
        private String  endpoint;
        private String  environmentShortname;
        private String  certificateFile;
        private String  keystoreFile;
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
            shouldSign = formData.getBoolean("shouldSign");
            endpoint   = formData.getString("endpoint");
            environmentShortname = formData.getString("environmentShortname");
            keystoreFile = formData.getString("keystoreFile") ;
            certificateFile = formData.getString("certificateFile");
            save();
            return super.configure(req, formData);
        }

        /**
         * This method returns true if the global configuration says we should sign messages.
         */
        public boolean getShouldSign() {
            return shouldSign;
        }

        /**
         * This method returns the endpoint we should connect to.
         */
        public String getEndpoint() {
            return endpoint;
        }

        /**
         * This method returns the shortname for the fedmsg environment (e.g. "prod", "dev", "stg")
         */
        public String getEnvironmentShortname() {
            return environmentShortname;
        }

        /**
         * @return the certificateFile
         */
        public String getCertificateFile() {
            return certificateFile;
        }

        /**
         * @param certificateFile the certificateFile to set
         */
        public void setCertificateFile(String certificateFile) {
            this.certificateFile = certificateFile;
        }

        /**
         * @return the keystoreFile
         */
        public String getKeystoreFile() {
            return keystoreFile;
        }

        /**
         * @param keystoreFile the keystoreFile to set
         */
        public void setKeystoreFile(String keystoreFile) {
            this.keystoreFile = keystoreFile;
        }
    }
}

