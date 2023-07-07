package com.kcftech.sd.credentials;

import com.amazonaws.auth.*;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder;
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest;
import com.amazonaws.services.securitytoken.model.Credentials;

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;

import org.apache.log4j.Logger;
import org.apache.log4j.LogManager;

import java.util.Date;
import java.util.Map;

/**
 * An AWS credential provider that allows hadoop cluster to assume a role when read/write
 * from S3 buckets.
 * <p>
 * The credential provider extracts from the URI the AWS role ARN that it should assume.
 * To encode the role ARN, we use a custom URI query key-value pair. The key is defined as
 * a constant {@link RoleBasedAWSCredentialProvider#AWS_ROLE_ARN_KEY}, the value is encoded
 * AWS role ARN string.
 * If the ARN string does not present, then fallback to default AWS credentials provider
 * </p>
 */
public class RoleBasedAWSCredentialProvider implements AWSCredentialsProvider, Configurable {

    /**
     * Name of the credential provider to use.
     */
    public final static String NAME = "com.kcftech.sd.credentials.RoleBasedAWSCredentialProvider";

    /**
     * Configuration key for the AWS Role ARN to use.
     */
    public final static String AWS_ROLE_ARN_KEY = "spark.com.kcftech.sd.credentials.AssumeRoleArn";

    /**
     * Configuration key for the Assume Role Session name prefix.
     */
    public final static String AWS_SESSION_PREFIX_KEY = "spark.com.kcftech.sd.credentials.SessionPrefix";

    /**
     * Time before expiry within which credentials will be renewed.
     */
    private static final int STS_CREDENTIAL_RENEW_BUFFER_MILLIS = 60 * 1000;

    /**
     * Life span of the temporary credential requested from STS
     */
    private static final int STS_CREDENTIAL_DURATION_SECONDS = 3600;

    private final Logger logger = LogManager.getLogger(RoleBasedAWSCredentialProvider.class);

    /**
     * The arn of the role to be assumed.
     */
    private final String roleArn;

    /**
     * The prefix to use when naming the session.
     */
    private final String sessionNamePrefix;

    /**
     * AWS security token service instance.
     */
    private final AWSSecurityTokenService securityTokenService;

    /**
     * Abstraction of System time function for testing purpose
     */
    private final TimeProvider timeProvider;

    /**
     * The expiration time for the current session credentials.
     */
    private Date sessionCredentialsExpiration;

    /**
     * The current session credentials.
     */
    private AWSSessionCredentials sessionCredentials;

    /**
     * Environment variable that may contain role to assume
     */
    private final String AWS_ROLE_ARN_ENV_VAR = "AWS_ROLE_ARN";

    /**
     * Create a {@link AWSCredentialsProvider} from an URI. The URI must contain a query parameter specifying
     * an AWS role ARN. The role is assumed to provide credentials for downstream operations.
     * <p>
     * The constructor signature must conform to hadoop calling convention exactly.
     * </p>
     *
     * @param configuration Hadoop configuration data
     */
    public RoleBasedAWSCredentialProvider(Configuration configuration) {
        this(configuration, AWSSecurityTokenServiceClientBuilder.defaultClient(), new SystemTimeProvider());
    }

    /**
     * Internal ctor for testing purpose
     *
     * @param configuration             Hadoop configuration data
     * @param securityTokenService      AWS Security Token Service
     * @param timeProvider              Function interface to provide system time
     */
    RoleBasedAWSCredentialProvider(Configuration configuration,
                                   AWSSecurityTokenService securityTokenService,
                                   TimeProvider timeProvider) {
        this.roleArn = configuration.get(AWS_ROLE_ARN_KEY);
        this.sessionNamePrefix = configuration.get(AWS_SESSION_PREFIX_KEY);

        this.securityTokenService = securityTokenService;
        this.timeProvider = timeProvider;
    }

    @Override
    public AWSCredentials getCredentials() {
        this.logger.debug("get credential called");

        if (this.roleArn == null) {
            this.logger.warn("assume role not provided");
            return null;
        }

        if (needsNewSession()) {
            startSession();
        }

        return sessionCredentials;
    }

    @Override
    public void refresh() {
        logger.debug("refresh called");
        startSession();
    }

    @Override
    public void setConf(Configuration configuration) {
    }

    @Override
    public Configuration getConf() {
        return null;
    }

    /**
     * Resolves the AWS Role ARN to use.
     * @return The AWS Role ARN to use.
     */
    private String resolveRoleArn() {
        String roleArn = this.roleArn;
        if (roleArn == null) {
            logger.warn("RoleBasedAWSCredentialProvider: No role provided via " + 
                        "Hadoop configuration. Checking environment variable " + this.AWS_ROLE_ARN_ENV_VAR + "...");
            
            Map<String, String> env = System.getenv();
            roleArn = env.get(this.AWS_ROLE_ARN_ENV_VAR);

            if (roleArn == null) {
                logger.warn("RoleBasedAWSCredentialProvider: Environment variable " + this.AWS_ROLE_ARN_ENV_VAR +
                            " not found. Not assuming a role.");
            } else {
                // This level is too high, but I want more visibility.
                // Eventually change to an info.
                logger.warn("RoleBasedAWSCredentialProvider: Using role ARN " + roleArn);
            }
        }

        return roleArn;
    }

    private String generateSessionName() {
        String sessionNamePrefix = this.sessionNamePrefix;
        if (sessionNamePrefix == null) {
            sessionNamePrefix = "role-based-credential-provider";
        }

        return sessionNamePrefix + String.valueOf(this.timeProvider.currentTimeMillis());
    }

    private AWSCredentials startSession() {
        try {
            String roleArn = this.resolveRoleArn();
            String sessionName = this.generateSessionName();

            AssumeRoleRequest assumeRoleRequest = new AssumeRoleRequest()
                    .withRoleArn(roleArn)
                    .withRoleSessionName(sessionName)
                    .withDurationSeconds(STS_CREDENTIAL_DURATION_SECONDS);
            Credentials stsCredentials = this.securityTokenService.assumeRole(assumeRoleRequest).getCredentials();
            sessionCredentials = new BasicSessionCredentials(stsCredentials.getAccessKeyId(),
                    stsCredentials.getSecretAccessKey(), stsCredentials.getSessionToken());
            sessionCredentialsExpiration = stsCredentials.getExpiration();
        }
        catch (Exception ex) {
            logger.warn("Unable to start a new session. Will use old session credential or fallback credential", ex);
        }

        return sessionCredentials;
    }

    private boolean needsNewSession() {
        if (sessionCredentials == null) {
            // Increased log level from debug to warn
            logger.warn("Session credentials do not exist. Needs new session");
            return true;
        }

        long timeRemaining = sessionCredentialsExpiration.getTime() - timeProvider.currentTimeMillis();
        if (timeRemaining < STS_CREDENTIAL_RENEW_BUFFER_MILLIS) {
            // Increased log level from debug to warn
            logger.warn("Session credential exist but expired. Needs new session");
            return true;
        } else {
            logger.info("Session credential exist and not expired. No need to create new session");
            return false;
        }
    }
}
