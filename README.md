Custom AWS credential provider
=======
A custom credential provider for Hadoop/Spark that assumes a configurable role name.
This library implements [custom AWS credential provider](http://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-plan-credentialsprovider.html) for your Hadoop or Spark applications on EMR, so it can access AWS resources with a configurable AWS assume role.

This is used at KCF to ensure multi-cluster Delta writes can be coordinated through a DynamoDB table
accessed via an assumed role.

# Usage
To use this library, follow these steps:
1. Download custom credential provider JAR or build it from source (you can use --packages).
2. Set `spark.io.delta.storage.S3DynamoDBLogStore.credentials.provider` to `spark.com.kcftech.sd.credentials.RoleBasedAWSCredentialProvider`.
3. Set `spark.com.kcftech.sd.credentials.AssumeRoleArn` to the AWS role name you plan to use:
```scala
spark.sparkContext.hadoopConfiguration.set("spark.com.kcftech.sd.credentials.AssumeRoleArn", jobArgs.params.getOrElse("roleName", "your-aws-role-arn"))
```

# Note
1. You should configure the role name before any call which uses the credential provider.
2. You can not change the assume role in the same Hadoop or Spark application. Once it's set at the beginning of the application, the role name stays fixed until the application finishes.

# How does custom credential provider work
When Hadoop/Spark applications access HDFS using `S3://` URI prefix, EMRFS will be initialized to handle the IO calls. At initialization, EMRFS looks for credential providers available including: (1) custom credential providers in current `CLASSPATH` (2) other default credential providers (local AWS profiles and EC2 profiles). After getting credential providers, EMRFS tries the credential providers according to their priority (custom providers have the highest priority). The first credential provider that returns a non-null `AWSCredentials` object wins and the credential provider will be cached for the entire application. HDFS will also periodically calls the cached credential provider to validate and renew the credentials.

Our custom credential provider gets the role name through `Hadoop` configuration mechanism, and calls `AWS Security Token Service (STS)` to return a valid `AWSCredentials` during initialization, and renews it during the entire application session.
