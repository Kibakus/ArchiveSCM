/*
# author: Denis Gabdulyanov (Kibakus) <den01246@gmail.com>
# description: Integrates Jenkins with Archive SCM
# copyright: 2024 LGPLv3
*/
package hudson.plugins.ArchiveSCM;


import com.cloudbees.plugins.credentials.common.*;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.annotation.CheckForNull;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogParser;
import hudson.scm.NullChangeLogParser;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.Util;

import org.jenkinsci.Symbol;
import jenkins.model.Jenkins;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;


import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;

import java.sql.Timestamp;
import java.time.Duration;

import java.util.Base64;
import java.util.Collections;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

@ExportedBean
public class ArchiveSCM extends SCM {

    private boolean ClearWorkspace;
    private boolean DeleteDistributive;
    private String ScmUrl;
    private String CredentialsId;

    @Exported
    public boolean isClearWorkspace() {
        return ClearWorkspace;
    }

    @Exported
    public boolean isDeleteDistributive() {
        return DeleteDistributive;
    }

    @Exported
    public String getScmUrl() {
        return ScmUrl;
    }

    @Exported
    public String getCredentialsId() {
        return CredentialsId;
    }

    @DataBoundConstructor
    public ArchiveSCM(boolean ClearWorkspace, final String ScmUrl, String CredentialsId) {
        setScmUrl(ScmUrl);
        setClearWorkspace(ClearWorkspace);
        setCredentialsId(CredentialsId);
    }

    @DataBoundSetter
    public void setClearWorkspace(final boolean  ClearWorkspace) {
        this.ClearWorkspace = ClearWorkspace;
    }
    @DataBoundSetter
    public void setDeleteDistributive(final boolean  DeleteDistributive) { this.DeleteDistributive = DeleteDistributive;}

    @DataBoundSetter
    public void setScmUrl(@CheckForNull final String ScmUrl) {
        this.ScmUrl = Util.fixEmptyAndTrim(ScmUrl);
    }

    @DataBoundSetter
    public void setCredentialsId(@CheckForNull String CredentialsId) {
        this.CredentialsId = CredentialsId;
    }

    @Override
    public void checkout(@NonNull Run<?, ?> build, @NonNull Launcher launcher, @NonNull FilePath workspace, @NonNull TaskListener listener, File changelogFile, SCMRevisionState baseline)
            throws IOException, InterruptedException {
        listener.getLogger().append("[ArchiveSCM] Checkout Start: " + new Timestamp(System.currentTimeMillis()) + "\n");
        if (ClearWorkspace) {
            listener.getLogger().append("[ArchiveSCM] ClearWorkspace\n");
            workspace.deleteContents();
        }
        try {
            downloadDistr(build,workspace,listener, getUsername(CredentialsId), getPassword(CredentialsId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        listener.getLogger().append("[ArchiveSCM] Checkout end: " + new Timestamp(System.currentTimeMillis()) + "\n");
    }

    public void downloadDistr(@NonNull Run<?, ?> build, @NonNull FilePath workspace, @NonNull TaskListener listener, String username, String password) throws Exception {
        listener.getLogger().append("[ArchiveSCM] Workspace: " + workspace + "\n");
        String urlString = ScmUrl;
        File FileSubDirDistributive;
        String filename = new File(urlString).getName();
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance != null) {
            File ParentFolder = new File(instance.getBuildDirFor(build.getParent()).getParent());
            listener.getLogger().append("[ArchiveSCM] BuildDirFolder:" + ParentFolder + "\n");
            FileSubDirDistributive = new File(ParentFolder, filename);
            listener.getLogger().append("[ArchiveSCM] Check MD5 file\n");
            if (checkFileDistrMD5(ParentFolder, FileSubDirDistributive, urlString, workspace, listener, username, password)) return;
            listener.getLogger().append("[ArchiveSCM] Download: " + urlString + "\n");
            downloadUsingNIO(urlString, FileSubDirDistributive, username, password);
            new FilePath(FileSubDirDistributive).unzip(workspace);
        }
    }

    public boolean checkFileDistrMD5(File ParentFolder, File FileSubDirDistributive, String urlString, FilePath workspace, TaskListener listener, String username, String password) throws IOException, InterruptedException {
        int count = 0;
        int maxTries = 3;
        while(true) {
            try {
                if (FileSubDirDistributive.exists()) {
                    if (DeleteDistributive) {
                        listener.getLogger().append("[ArchiveSCM] DeleteDistributive True\n");
                        deleteOldDistr(ParentFolder, FileSubDirDistributive, listener);
                    }
                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(urlString + ".md5"))
                            .timeout(Duration.ofSeconds(60))
                            .GET()
                            .header("Authorization", getBasicAuthenticationHeader(username, password))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        if (new FilePath(FileSubDirDistributive).digest().equals(response.body())) {
                            listener.getLogger().append("[ArchiveSCM] Good match cache: ").append(String.valueOf(FileSubDirDistributive)).append("\n");
                            new FilePath(FileSubDirDistributive).unzip(workspace);
                            return true;
                        } else {
                            listener.getLogger().append("[ArchiveSCM] Bad match cache: " + FileSubDirDistributive + "\n");
                            return false;
                        }
                    }else{
                        listener.getLogger().append("[ArchiveSCM] Function CheckFileDistrMD5 " + urlString + " statusCode: " + response.statusCode() + "\n");
                        return true;
                    }
                }
                else{
                    listener.getLogger().append("[ArchiveSCM] No found cache: " + FileSubDirDistributive + "\n");
                    return false;
                }
            } catch (IOException e) {
                if (count == 0) listener.error("IOException: function CheckFileDistrMD5 " + urlString + "\n" + e.getMessage()+ "\n");
                if (++count == maxTries) {
                    listener.getLogger().append("[ArchiveSCM] Execute cache file: " + FileSubDirDistributive + "\n");
                    new FilePath(FileSubDirDistributive).unzip(workspace);
                    return true;
                }
            }
        }
    }

    public void deleteOldDistr(File ParentFolder, File filename, TaskListener listener) {
        File[] files = ParentFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (files != null) {
            for (File file : files) {
                if (!file.getName().equalsIgnoreCase(filename.getName())) {
                    if (!file.delete()) {
                        listener.getLogger().append("[ArchiveSCM] Failed to delete file: " + file.getName() + "\n");
                    }else {
                        listener.getLogger().append("[ArchiveSCM] Delete old file: " + file.getName() + "\n");
                    }
                }
            }
        }

    }

    private static void downloadUsingNIO(String urlStr, File file, String username, String password) throws Exception {
        try {
            URL url = new URL(urlStr);
            URLConnection conn = url.openConnection();
            conn.setRequestProperty("Authorization", getBasicAuthenticationHeader(username, password));
            ReadableByteChannel rbc = Channels.newChannel(conn.getInputStream());
            FileOutputStream fos = new FileOutputStream(file);
            fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            fos.close();
            rbc.close();
        } catch (Exception e) {
            throw new Exception("\nURL: " + urlStr + "\nError: " + e.getMessage());
        }
    }

    private static String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return new NullChangeLogParser();
    }

    @Override
    public boolean requiresWorkspaceForPolling() {
        // this plugin does the polling work via the data in the Run
        // the data in the workspace is not used
        return false;
    }

    @Override
    public SCMRevisionState calcRevisionsFromBuild(@NonNull Run<?, ?> abstractBuild, FilePath workspace, Launcher launcher, @NonNull TaskListener taskListener) {
        return SCMRevisionState.NONE;
    }

    public static String getUsername(String CredentialsId) {
        StandardUsernamePasswordCredentials credentials = resolveCredentials(CredentialsId);
        return credentials == null ? "" : credentials.getUsername();
    }

    public static String getPassword(String CredentialsId) {
        StandardUsernamePasswordCredentials credentials = resolveCredentials(CredentialsId);
        return credentials == null ? "" : Secret.toString(credentials.getPassword());
    }

    private static StandardUsernamePasswordCredentials resolveCredentials(String CredentialsId) {
        if (StringUtils.isBlank(CredentialsId)) {
            return null;
        } else {
            return CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            StandardUsernamePasswordCredentials.class,
                            Jenkins.get(),
                            ACL.SYSTEM2,
                            Collections.emptyList()),
                    CredentialsMatchers.withId(CredentialsId));
        }
    }


    @Extension
    @Symbol("ArchiveSCM")
    public static final class DescriptorImpl extends SCMDescriptor<ArchiveSCM> {
        public DescriptorImpl() {
            super(ArchiveSCM.class, null);
            load();
        }

        @NonNull
        @Override
        public String getDisplayName(){
            return "ArchiveSCM";
        }


        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) {
            req.bindJSON(this, formData);
            save();
            return true;
        }
        @Override
        public boolean isApplicable(Job project) {
            // All job types are supported, the plugin does not depend on
            // AbstractProject/AbstractBuild anymore
            return true;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(ACL.SYSTEM2, context, StandardUsernamePasswordCredentials.class, Collections.emptyList(), CredentialsMatchers.always())
                    .includeMatchingAs(ACL.SYSTEM2, Jenkins.get(), StandardUsernamePasswordCredentials.class, Collections.emptyList(), CredentialsMatchers.always());

        }

        @POST
        public FormValidation doTestConnection(@QueryParameter final String ScmUrl, @QueryParameter final String CredentialsId)
                throws InterruptedException {
            /*Logger LOGGER = Logger.getLogger("InfoLogging");
            LOGGER.info("This will print in stdout: " + ScmUrl);*/
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ScmUrl))
                        .timeout(Duration.ofSeconds(60))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", getBasicAuthenticationHeader(getUsername(CredentialsId), getPassword(CredentialsId)))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return FormValidation.ok("statusCode: " + response.statusCode());
                }else{
                    return FormValidation.error("URL: " + ScmUrl + "\nstatusCode: " + response.statusCode() + "\nbody: " + response.body());
                }
            } catch (IOException e) {
                return FormValidation.error("IOException: " + e.getMessage());
            }
        }
    }
}