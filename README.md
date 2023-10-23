Custom AWS credential provider for Delta Spark
=======
A custom credential provider for Delta on Spark that assumes a configurable role name, based on [Zillow's custom EMRFS credential provider](https://github.com/zillow/aws-custom-credential-provider).
This library implements [custom AWS credential provider](http://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-plan-credentialsprovider.html) for your Hadoop or Spark applications on EMR, so it can access AWS resources with a configurable AWS assume role.

This is used at KCF to ensure multi-cluster Delta writes can be coordinated through a DynamoDB table
accessed via an assumed role.

# Usage
To use this library, follow these steps:
1. Download custom credential provider JAR or build it from source (you can use `--packages`).
2. Set `spark.io.delta.storage.S3DynamoDBLogStore.credentials.provider` to `com.kcftech.sd.credentials.RoleBasedAWSCredentialProvider`.
3. Set `spark.com.kcftech.sd.credentials.AssumeRoleArn` to the AWS role name you plan to use:
```scala
spark.sparkContext.hadoopConfiguration.set("spark.com.kcftech.sd.credentials.AssumeRoleArn", jobArgs.params.getOrElse("roleName", "your-aws-role-arn"))
```

# Note
1. You should configure the role name before any call which uses the credential provider.
2. You can not change the assume role in the same Hadoop or Spark application. Once it's set at the beginning of the application, the role name stays fixed until the application finishes.
