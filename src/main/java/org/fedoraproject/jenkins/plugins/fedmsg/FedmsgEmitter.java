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

import fj.F;
import fj.Unit;
import fj.data.Either;
import fj.data.IO;
import fj.data.Option;

/**
 * Send a message to the Fedmsg bus when a build is completed.
 *
 * @author Ricky Elrod
 */
public class FedmsgEmitter extends Notifier {

    private static final Logger LOGGER = Logger.getLogger("FedmsgEmitter");

    @DataBoundConstructor
    public FedmsgEmitter() { }

    private Option<String> statusToFedmsg(String s) {
        // http://javadoc.jenkins-ci.org/hudson/model/Result.html
        // Wouldn't it be great if Java had pattern matching?
        if (s.equals("SUCCESS"))
            return Option.some("passed");
        else if (s.equals("FAILURE"))
            return Option.some("failed");
        else if (s.equals("ABORTED"))
            return Option.some("aborted");
        else if (s.equals("NOT_BUILT"))
            return Option.some("notbuilt");
        else if (s.equals("UNSTABLE"))
            return Option.some("unstable");
        return Option.none();
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
        final String endpoint    = getDescriptor().getEndpoint();
        final String topicPrefix = getDescriptor().getTopicPrefix();
        final String environment = getDescriptor().getEnvironmentShortname();
        final String cert        = getDescriptor().getCertificateFile();
        final String key         = getDescriptor().getKeystoreFile();

        final FedmsgConnection fedmsg = new FedmsgConnection(endpoint, 2000).connect();

        Either<Exception, Result> buildResult = Option.fromNull(build.getResult()).toEither(new Exception("left"));

        // /!\ ooooooh scary! monadic bind! /!\  :-)
        Either<Exception, Result> res =
            buildResult.right().bind(new F<Result, Either<Exception, Result>>() {
                public Either<Exception, Result> f(final Result r) {
                    HashMap<String, Object> message = new HashMap();
                    // .split(...) always returns a nonempty list, even for
                    // empty strings, so we are justified in our 0-index below.
                    // Well, not justified, but we're using Java so we're
                    // already screwed.
                    String[] projectName = build.getProject().getFullDisplayName().split(" \u00bb ");
                    message.put("project", projectName[0]);
                    message.put("build", build.getNumber());
                    if (projectName.length > 1)
                        message.put("configuration", projectName[1]);

                    String status = statusToFedmsg(r.toString()).orSome("unknown");

                    FedmsgMessage blob = new FedmsgMessage(
                         message,
                         topicPrefix +"." + environment + ".jenkins.build." + status,
                         (new java.util.Date()).getTime() / 1000,
                         1);

                    try {
                        LOGGER.log(Level.SEVERE, "MSG: " + blob.toJson().toString());
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Error converting (unsigned) message to JSON.");
                        return Either.left(e);
                    }

                    try {
                        if (getDescriptor().getShouldSign()) {
                            final IO<Either<Exception, SignedFedmsgMessage>> signedIO =
                                blob.sign(
                                    new File(cert),
                                    new File(key));
                            signedIO.map(
                                new F<Either<Exception, SignedFedmsgMessage>, IO<Unit>>() {
                                    public IO<Unit> f(final Either<Exception, SignedFedmsgMessage> em) {
                                        return em.either(
                                            new F<Exception, IO<Unit>>() {
                                                public IO<Unit> f(final Exception e) {
                                                    return new IO<Unit>() {
                                                        public Unit run() {
                                                            LOGGER.log(Level.SEVERE, "ERROR SIGNING: ", e);
                                                            return Unit.unit();
                                                        }
                                                    };
                                                }
                                            },
                                            new F<SignedFedmsgMessage, IO<Unit>>() {
                                                public IO<Unit> f(final SignedFedmsgMessage m) {
                                                    return new IO<Unit>() {
                                                        public Unit run() {
                                                            try {
                                                                fedmsg.send(m);
                                                                LOGGER.log(Level.SEVERE, "SENT: " + m.toJson().toString());
                                                            } catch (java.io.IOException e) {
                                                                LOGGER.log(Level.SEVERE, "ERROR: " + e.toString());
                                                            }
                                                            return Unit.unit();
                                                        }
                                                    };
                                                }
                                            });
                                    }
                                }).run().run();
                        } else {
                            LOGGER.log(Level.SEVERE, "SENT: " + blob.toJson().toString());
                            fedmsg.send(blob);
                        }
                        return Either.right(r);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, "Unable to send to fedmsg.", e);
                        return Either.left(e);
                    }
                }
        });

        return res.isRight();
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
        private String  topicPrefix;
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
            topicPrefix = formData.getString("topicPrefix");
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
         * This method returns the topic prefix (toplevel namespace for the message)
         */
        public String getTopicPrefix(){
            return topicPrefix;
        }
        
        /**
         * This method returns the default topic prefix if one is not previously defined
         */
        public String defaultTopicPrefix(){
            return "org.fedoraproject";
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
