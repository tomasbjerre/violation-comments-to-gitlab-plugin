package org.jenkinsci.plugins.jvctgl.perform;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Throwables.propagate;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.logging.Level.SEVERE;
import static org.gitlab.api.AuthMethod.HEADER;
import static org.gitlab.api.AuthMethod.URL_PARAMETER;
import static org.gitlab.api.TokenType.ACCESS_TOKEN;
import static org.gitlab.api.TokenType.PRIVATE_TOKEN;
import static org.jenkinsci.plugins.jvctgl.config.CredentialsHelper.findApiTokenCredentials;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_APITOKEN;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_APITOKENCREDENTIALSID;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_APITOKENPRIVATE;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_AUTHMETHODHEADER;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_COMMENTONLYCHANGEDCONTENT;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_CREATECOMMENTWITHALLSINGLEFILECOMMENTS;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_GITLABURL;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_IGNORECERTIFICATEERRORS;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_MERGEREQUESTID;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_PROJECTID;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_USEAPITOKEN;
import static org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfigHelper.FIELD_USEAPITOKENCREDENTIALS;
import static se.bjurr.violations.comments.gitlab.lib.ViolationCommentsToGitLabApi.violationCommentsToGitLabApi;
import static se.bjurr.violations.lib.ViolationsReporterApi.violationsReporterApi;
import static se.bjurr.violations.lib.parsers.FindbugsParser.setFindbugsMessagesXml;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.util.List;
import java.util.logging.Logger;

import org.gitlab.api.AuthMethod;
import org.gitlab.api.TokenType;
import org.jenkinsci.plugins.jvctgl.config.ViolationConfig;
import org.jenkinsci.plugins.jvctgl.config.ViolationsToGitLabConfig;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.remoting.RoleChecker;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.io.CharStreams;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import se.bjurr.violations.lib.model.Violation;

public class JvctglPerformer {

  @VisibleForTesting
  public static void doPerform(
      final ViolationsToGitLabConfig config, final File workspace, final TaskListener listener)
      throws MalformedURLException {
    if (config.getMergeRequestId() == null) {
      listener
          .getLogger()
          .println("No merge request id defined, will not send violation comments.");
      return;
    }

    final List<Violation> allParsedViolations = newArrayList();
    for (final ViolationConfig violationConfig : config.getViolationConfigs()) {
      if (!isNullOrEmpty(violationConfig.getPattern())) {
        final List<Violation> parsedViolations =
            violationsReporterApi() //
                .findAll(violationConfig.getReporter()) //
                .inFolder(workspace.getAbsolutePath()) //
                .withPattern(violationConfig.getPattern()) //
                .violations();
        allParsedViolations.addAll(parsedViolations);
        listener
            .getLogger()
            .println(
                "Found " + parsedViolations.size() + " violations from " + violationConfig + ".");
      }
    }

    String apiToken =
        checkNotNull(emptyToNull(config.getApiToken()), "APIToken selected but not set!");
    String hostUrl = config.getGitLabUrl();
    String projectId = config.getProjectId();
    String mergeRequestId = config.getMergeRequestId();

    listener
        .getLogger()
        .println("Will comment PR " + hostUrl + " " + projectId + " " + mergeRequestId);

    try {
      TokenType tokenType = config.getApiTokenPrivate() ? PRIVATE_TOKEN : ACCESS_TOKEN;
      AuthMethod authMethod = config.getAuthMethodHeader() ? HEADER : URL_PARAMETER;
      Integer projectIdInteger = Integer.parseInt(projectId);
      Integer mergeRequestIdInteger = Integer.parseInt(mergeRequestId);
      violationCommentsToGitLabApi() //
          .setHostUrl(hostUrl) //
          .setProjectId(projectIdInteger) //
          .setMergeRequestId(mergeRequestIdInteger) //
          .setApiToken(apiToken) //
          .setTokenType(tokenType) //
          .setMethod(authMethod) //
          .setCommentOnlyChangedContent(config.getCommentOnlyChangedContent()) //
          .setCreateCommentWithAllSingleFileComments(
              config.getCreateCommentWithAllSingleFileComments()) //
          /**
           * Cannot yet support this because the API does not support it.
           * https://gitlab.com/gitlab-org/gitlab-ce/issues/14850
           */
          .setIgnoreCertificateErrors(config.getIgnoreCertificateErrors()) //
          .setViolations(allParsedViolations) //
          .toPullRequest();
    } catch (final Exception e) {
      Logger.getLogger(JvctglPerformer.class.getName()).log(SEVERE, "", e);
      final StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      listener.getLogger().println(sw.toString());
    }
  }

  /** Makes sure any Jenkins variable, used in the configuration fields, are evaluated. */
  @VisibleForTesting
  static ViolationsToGitLabConfig expand(
      final ViolationsToGitLabConfig config, final EnvVars environment) {
    final ViolationsToGitLabConfig expanded = new ViolationsToGitLabConfig();
    expanded.setGitLabUrl(environment.expand(config.getGitLabUrl()));
    expanded.setProjectId(environment.expand(config.getProjectId()));
    expanded.setMergeRequestId(environment.expand(config.getMergeRequestId()));

    expanded.setUseApiToken(config.getUseApiToken());
    expanded.setApiToken(config.getApiToken());

    expanded.setUseApiTokenCredentials(config.isUseApiTokenCredentials());
    expanded.setApiTokenCredentialsId(config.getApiTokenCredentialsId());

    expanded.setAuthMethodHeader(config.getAuthMethodHeader());
    expanded.setApiTokenPrivate(config.getApiTokenPrivate());
    expanded.setIgnoreCertificateErrors(config.getIgnoreCertificateErrors());

    expanded.setCommentOnlyChangedContent(config.getCommentOnlyChangedContent());
    expanded.setCreateCommentWithAllSingleFileComments(
        config.getCreateCommentWithAllSingleFileComments());

    for (final ViolationConfig violationConfig : config.getViolationConfigs()) {
      final ViolationConfig p = new ViolationConfig();
      p.setPattern(environment.expand(violationConfig.getPattern()));
      p.setReporter(violationConfig.getReporter());
      expanded.getViolationConfigs().add(p);
    }
    return expanded;
  }

  public static void jvctsPerform(
      final ViolationsToGitLabConfig configUnexpanded,
      final FilePath fp,
      final Run<?, ?> build,
      final TaskListener listener) {
    try {
      final EnvVars env = build.getEnvironment(listener);
      final ViolationsToGitLabConfig configExpanded = expand(configUnexpanded, env);
      listener.getLogger().println("---");
      listener.getLogger().println("--- Violation Comments to GitLab ---");
      listener.getLogger().println("---");
      logConfiguration(configExpanded, build, listener);

      setApiTokenCredentials(configExpanded, listener);

      listener.getLogger().println("Running Violation Comments To GitLab");
      listener.getLogger().println("Will comment " + configExpanded.getMergeRequestId());

      fp.act(
          new FileCallable<Void>() {

            private static final long serialVersionUID = -7686563245942529513L;

            @Override
            public void checkRoles(final RoleChecker checker) throws SecurityException {}

            @Override
            public Void invoke(final File workspace, final VirtualChannel channel)
                throws IOException, InterruptedException {
              setupFindBugsMessages();
              listener.getLogger().println("Workspace: " + workspace.getAbsolutePath());
              doPerform(configExpanded, workspace, listener);
              return null;
            }
          });
    } catch (final Exception e) {
      Logger.getLogger(JvctglPerformer.class.getName()).log(SEVERE, "", e);
      final StringWriter sw = new StringWriter();
      e.printStackTrace(new PrintWriter(sw));
      listener.getLogger().println(sw.toString());
      return;
    }
  }

  private static void logConfiguration(
      final ViolationsToGitLabConfig config, final Run<?, ?> build, final TaskListener listener) {
    listener.getLogger().println(FIELD_GITLABURL + ": " + config.getGitLabUrl());
    listener.getLogger().println(FIELD_PROJECTID + ": " + config.getProjectId());
    listener.getLogger().println(FIELD_MERGEREQUESTID + ": " + config.getMergeRequestId());

    listener.getLogger().println(FIELD_USEAPITOKEN + ": " + config.getUseApiToken());
    listener.getLogger().println(FIELD_APITOKEN + ": " + !isNullOrEmpty(config.getApiToken()));

    listener
        .getLogger()
        .println(FIELD_USEAPITOKENCREDENTIALS + ": " + config.isUseApiTokenCredentials());
    listener
        .getLogger()
        .println(
            FIELD_APITOKENCREDENTIALSID + ": " + !isNullOrEmpty(config.getApiTokenCredentialsId()));
    listener
        .getLogger()
        .println(FIELD_IGNORECERTIFICATEERRORS + ": " + config.getIgnoreCertificateErrors());
    listener.getLogger().println(FIELD_APITOKENPRIVATE + ": " + config.getApiTokenPrivate());
    listener.getLogger().println(FIELD_AUTHMETHODHEADER + ": " + config.getAuthMethodHeader());

    listener
        .getLogger()
        .println(
            FIELD_CREATECOMMENTWITHALLSINGLEFILECOMMENTS
                + ": "
                + config.getCreateCommentWithAllSingleFileComments());
    listener
        .getLogger()
        .println(FIELD_COMMENTONLYCHANGEDCONTENT + ": " + config.getCommentOnlyChangedContent());

    for (final ViolationConfig violationConfig : config.getViolationConfigs()) {
      listener
          .getLogger()
          .println(violationConfig.getReporter() + " with pattern " + violationConfig.getPattern());
    }
  }

  private static void setApiTokenCredentials(
      final ViolationsToGitLabConfig configExpanded, final TaskListener listener) {
    if (configExpanded.isUseApiTokenCredentials()) {
      final String getoApiTokenCredentialsId = configExpanded.getApiTokenCredentialsId();
      if (!isNullOrEmpty(getoApiTokenCredentialsId)) {
        final Optional<StringCredentials> credentials =
            findApiTokenCredentials(getoApiTokenCredentialsId);
        if (credentials.isPresent()) {
          final StringCredentials stringCredential =
              checkNotNull(credentials.get(), "Credentials API token selected but not set!");
          configExpanded.setApiToken(stringCredential.getSecret().getPlainText());
          listener.getLogger().println("Using API token from credentials");
        } else {
          listener.getLogger().println("API token credentials not found!");
          return;
        }
      } else {
        listener.getLogger().println("API token credentials checked but not selected!");
        return;
      }
    }
  }

  private static void setupFindBugsMessages() {
    try {
      final String findbugsMessagesXml =
          CharStreams.toString(
              new InputStreamReader(
                  JvctglPerformer.class.getResourceAsStream("findbugs-messages.xml"), UTF_8));
      setFindbugsMessagesXml(findbugsMessagesXml);
    } catch (final IOException e) {
      propagate(e);
    }
  }
}
