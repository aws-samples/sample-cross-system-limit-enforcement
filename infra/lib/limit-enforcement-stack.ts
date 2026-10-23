import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as eks from 'aws-cdk-lib/aws-eks';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as elasticache from 'aws-cdk-lib/aws-elasticache';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as ecrAssets from 'aws-cdk-lib/aws-ecr-assets';
import * as apigwv2 from 'aws-cdk-lib/aws-apigatewayv2';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { HttpAlbIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import { HttpJwtAuthorizer } from 'aws-cdk-lib/aws-apigatewayv2-authorizers';
import { KubectlV30Layer } from '@aws-cdk/lambda-layer-kubectl-v30';

export interface LimitEnforcementStackProps extends cdk.StackProps { }

/**
 * Single-stack, one-`cdk deploy` demo infrastructure for the cross-system
 * daily-transfer-limit enforcement service.
 *
 *   client --HTTPS--> API Gateway (HTTP API, Cognito JWT auth)
 *          --VPC Link--> internal ALB --> EKS pods (Spring Boot :8080)
 *
 * Data:  Aurora MySQL Serverless v2 (reservation ledger) + ElastiCache Redis
 *        (legacy-total cache). DB creds live in Secrets Manager and are synced
 *        into the cluster by the External Secrets Operator via IRSA.
 *
 * On-prem is simulated by a mock "legacy core" Deployment in its own namespace.
 */
export class LimitEnforcementStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: LimitEnforcementStackProps) {
    super(scope, id, props);

    // Destructive demo teardown is OPT-IN only. The safe configuration
    // (RETAIN, deletion protection, backups, secret rotation) is the default;
    // pass `-c demoTeardown=true` to get the clean-teardown demo behaviour.
    const demoTeardown =
      this.node.tryGetContext('demoTeardown') === 'true' ||
      this.node.tryGetContext('demoTeardown') === true;

    // SD-1: server-side principal -> customer bindings ("sub=customerId" pairs,
    // comma-separated). Empty by default, which rejects every transfer with 403
    // until a binding is supplied: `-c principalCustomerMap=<sub>=<customerId>`.
    const principalCustomerMap: string =
      this.node.tryGetContext('principalCustomerMap') ?? '';

    // SD-7: stack-wide tags for cost allocation, ownership, and data
    // classification. Propagated onto every taggable resource in the stack.
    cdk.Tags.of(this).add('Owner', 'limit-enforcement-team');
    cdk.Tags.of(this).add('Environment', 'demo');
    cdk.Tags.of(this).add('CostCenter', 'limit-enforcement');
    cdk.Tags.of(this).add('DataClassification', 'confidential-financial');

    const appNamespace = 'app';
    const legacyNamespace = 'legacy';

    // -------------------------------------------------------------------------
    // 1. Network
    // -------------------------------------------------------------------------
    const vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 2,
      natGateways: 1, // single NAT to keep demo cost down
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'private', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 22 },
      ],
    });

    // -------------------------------------------------------------------------
    // 2. EKS cluster (managed node group + IRSA + AWS Load Balancer Controller)
    // -------------------------------------------------------------------------
    const cluster = new eks.Cluster(this, 'Cluster', {
      version: eks.KubernetesVersion.V1_30,
      kubectlLayer: new KubectlV30Layer(this, 'KubectlLayer'),
      vpc,
      vpcSubnets: [{ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }],
      defaultCapacity: 2,
      defaultCapacityInstance: ec2.InstanceType.of(ec2.InstanceClass.T3, ec2.InstanceSize.MEDIUM),
      // Installs the AWS Load Balancer Controller with the correct IAM policy.
      // Provides the TargetGroupBinding CRD used to wire the ALB to the Service.
      albController: { version: eks.AlbControllerVersion.V2_8_2 },
      clusterLogging: [
        eks.ClusterLoggingTypes.API,
        eks.ClusterLoggingTypes.AUDIT,
        eks.ClusterLoggingTypes.AUTHENTICATOR,
      ],
    });

    // -------------------------------------------------------------------------
    // SD-4: customer-managed KMS key + explicit retention for the EKS
    // control-plane CloudWatch log group.
    // -------------------------------------------------------------------------
    // The eks.Cluster construct writes control-plane logs to
    // /aws/eks/<clusterName>/cluster with AWS-managed encryption and no explicit
    // retention. Create a customer-managed key (rotation enabled) and an
    // explicit log group so the control-plane logs are encrypted under a key we
    // own and audit, with a bounded retention period.
    const logsKey = new kms.Key(this, 'EksLogsKey', {
      description: 'CMK for EKS control-plane CloudWatch log groups',
      enableKeyRotation: true,
      removalPolicy: demoTeardown ? cdk.RemovalPolicy.DESTROY : cdk.RemovalPolicy.RETAIN,
    });
    // Allow the CloudWatch Logs service in this region to use the key for the
    // log group's encrypt/decrypt operations.
    logsKey.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'AllowCloudWatchLogs',
        principals: [new iam.ServicePrincipal(`logs.${this.region}.amazonaws.com`)],
        actions: [
          'kms:Encrypt',
          'kms:Decrypt',
          'kms:ReEncrypt*',
          'kms:GenerateDataKey*',
          'kms:Describe*',
        ],
        resources: ['*'],
        conditions: {
          ArnLike: {
            'kms:EncryptionContext:aws:logs:arn': `arn:aws:logs:${this.region}:${this.account}:log-group:/aws/eks/${cluster.clusterName}/cluster`,
          },
        },
      }),
    );
    new logs.LogGroup(this, 'EksClusterLogGroup', {
      logGroupName: `/aws/eks/${cluster.clusterName}/cluster`,
      encryptionKey: logsKey,
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: demoTeardown ? cdk.RemovalPolicy.DESTROY : cdk.RemovalPolicy.RETAIN,
    });

    // Namespaces
    const nsApp = cluster.addManifest('NsApp', {
      apiVersion: 'v1',
      kind: 'Namespace',
      metadata: { name: appNamespace },
    });
    const nsLegacy = cluster.addManifest('NsLegacy', {
      apiVersion: 'v1',
      kind: 'Namespace',
      metadata: { name: legacyNamespace },
    });

    // -------------------------------------------------------------------------
    // 3. Data layer: Aurora MySQL (Serverless v2) + ElastiCache Redis
    // -------------------------------------------------------------------------
    const dbSg = new ec2.SecurityGroup(this, 'AuroraSg', {
      vpc,
      description: 'Aurora MySQL access',
      allowAllOutbound: true,
    });
    const redisSg = new ec2.SecurityGroup(this, 'RedisSg', {
      vpc,
      description: 'ElastiCache Redis access',
      allowAllOutbound: true,
    });

    // Pods egress through the EKS cluster security group (VPC CNI primary ENI),
    // so allow the data stores to accept traffic from it.
    const clusterSgSource = ec2.Peer.securityGroupId(cluster.clusterSecurityGroupId);
    dbSg.addIngressRule(clusterSgSource, ec2.Port.tcp(3306), 'MySQL from EKS pods');
    redisSg.addIngressRule(clusterSgSource, ec2.Port.tcp(6379), 'Redis from EKS pods');

    const aurora = new rds.DatabaseCluster(this, 'Aurora', {
      engine: rds.DatabaseClusterEngine.auroraMysql({
        // Pass BOTH full and major version: .of(full) alone defaults the major
        // to 5.7, which points at a non-existent aurora-mysql5.7 param group.
        version: rds.AuroraMysqlEngineVersion.of('8.0.mysql_aurora.3.08.2', '8.0'),
      }),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [dbSg],
      writer: rds.ClusterInstance.serverlessV2('writer'),
      serverlessV2MinCapacity: 0.5,
      serverlessV2MaxCapacity: 2,
      defaultDatabaseName: 'limits',
      credentials: rds.Credentials.fromGeneratedSecret('admin'),
      storageEncrypted: true,
      // SD-5: safe by default. Destructive teardown is opt-in via
      // `-c demoTeardown=true`.
      removalPolicy: demoTeardown ? cdk.RemovalPolicy.DESTROY : cdk.RemovalPolicy.RETAIN,
      deletionProtection: !demoTeardown,
      backup: { retention: demoTeardown ? cdk.Duration.days(1) : cdk.Duration.days(7) },
    });
    const dbSecret = aurora.secret!;
    // SD-5: rotate the generated DB credential automatically in the safe
    // (default) configuration. Skipped under demoTeardown to keep the
    // throwaway demo stack lightweight.
    if (!demoTeardown) {
      aurora.addRotationSingleUser();
    }

    // ElastiCache Redis (single node, demo)
    const redisSubnets = new elasticache.CfnSubnetGroup(this, 'RedisSubnets', {
      description: 'Private subnets for Redis',
      subnetIds: vpc.selectSubnets({ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }).subnetIds,
      cacheSubnetGroupName: 'limit-enforcement-redis',
    });
    // SD-2: dedicated Secrets Manager secret holding the Redis AUTH token.
    // Delivered to the app through the same External Secrets Operator / IRSA
    // path as the DB credentials. ElastiCache AUTH tokens must avoid certain
    // characters, so exclude them from the generated value.
    const redisAuthSecret = new secretsmanager.Secret(this, 'RedisAuthToken', {
      description: 'ElastiCache Redis AUTH token for the limit-enforcement cache',
      generateSecretString: {
        passwordLength: 32,
        excludePunctuation: true,
        excludeCharacters: '/@" \\',
      },
      removalPolicy: demoTeardown ? cdk.RemovalPolicy.DESTROY : cdk.RemovalPolicy.RETAIN,
    });

    // A single-node replication group (not CfnCacheCluster) is used so that
    // at-rest encryption can be enabled — CfnCacheCluster does not expose
    // atRestEncryptionEnabled; only replication groups support it.
    // SD-2 adds in-transit encryption + AUTH on top of MF-1's at-rest setting.
    const redis = new elasticache.CfnReplicationGroup(this, 'Redis', {
      replicationGroupDescription: 'Limit enforcement legacy-total cache',
      engine: 'redis',
      cacheNodeType: 'cache.t4g.micro',
      numCacheClusters: 1,
      cacheSubnetGroupName: redisSubnets.ref,
      securityGroupIds: [redisSg.securityGroupId],
      atRestEncryptionEnabled: true,
      transitEncryptionEnabled: true,
      authToken: redisAuthSecret.secretValue.unsafeUnwrap(),
    });
    redis.node.addDependency(redisSubnets);

    // -------------------------------------------------------------------------
    // 4. Container images (built + pushed to ECR during `cdk deploy`)
    // -------------------------------------------------------------------------
    const repoRoot = path.join(__dirname, '..', '..');
    const appImage = new ecrAssets.DockerImageAsset(this, 'AppImage', {
      directory: repoRoot,
      file: 'Dockerfile',
      platform: ecrAssets.Platform.LINUX_AMD64,
    });
    const mockImage = new ecrAssets.DockerImageAsset(this, 'MockImage', {
      directory: path.join(repoRoot, 'mock-legacy'),
      file: 'Dockerfile',
      platform: ecrAssets.Platform.LINUX_AMD64,
    });

    // -------------------------------------------------------------------------
    // 5. Secrets: IRSA service account + External Secrets Operator
    // -------------------------------------------------------------------------
    // App service account with IRSA; allowed to read the DB secret. The ESO
    // SecretStore references this SA to authenticate to Secrets Manager.
    const appServiceAccount = cluster.addServiceAccount('AppSa', {
      name: 'transfer-service',
      namespace: appNamespace,
    });
    appServiceAccount.node.addDependency(nsApp);
    dbSecret.grantRead(appServiceAccount.role);
    // SD-2: the app also reads the Redis AUTH token through this same IRSA role.
    redisAuthSecret.grantRead(appServiceAccount.role);

    // SD-8: give the IRSA service-account role an explicit, collision-safe name
    // and description so least-privilege reviews are traceable. The name is
    // prefixed with the stack name to stay unique across repeated deploys.
    const appSaCfnRole = appServiceAccount.role.node.defaultChild as iam.CfnRole;
    appSaCfnRole.roleName = `${this.stackName}-transfer-service-sa`;
    appSaCfnRole.description =
      'IRSA role for the transfer-service pods: reads the DB and Redis AUTH secrets from Secrets Manager';

    // External Secrets Operator (syncs Secrets Manager -> k8s Secret)
    const eso = cluster.addHelmChart('ExternalSecrets', {
      chart: 'external-secrets',
      repository: 'https://charts.external-secrets.io',
      release: 'external-secrets',
      namespace: 'external-secrets',
      createNamespace: true,
      version: '0.10.4',
      values: { installCRDs: true },
    });

    // SecretStore authenticating via the app SA's IRSA role.
    const secretStore = cluster.addManifest('DbSecretStore', {
      apiVersion: 'external-secrets.io/v1beta1',
      kind: 'SecretStore',
      metadata: { name: 'aws-secrets', namespace: appNamespace },
      spec: {
        provider: {
          aws: {
            service: 'SecretsManager',
            region: this.region,
            auth: {
              jwt: { serviceAccountRef: { name: 'transfer-service' } },
            },
          },
        },
      },
    });
    secretStore.node.addDependency(eso, appServiceAccount);

    // ExternalSecret -> creates k8s Secret "db-credentials" with username/password.
    const externalSecret = cluster.addManifest('DbExternalSecret', {
      apiVersion: 'external-secrets.io/v1beta1',
      kind: 'ExternalSecret',
      metadata: { name: 'db-credentials', namespace: appNamespace },
      spec: {
        refreshInterval: '1h',
        secretStoreRef: { name: 'aws-secrets', kind: 'SecretStore' },
        target: { name: 'db-credentials', creationPolicy: 'Owner' },
        data: [
          {
            secretKey: 'username',
            remoteRef: { key: dbSecret.secretName, property: 'username' },
          },
          {
            secretKey: 'password',
            remoteRef: { key: dbSecret.secretName, property: 'password' },
          },
        ],
      },
    });
    externalSecret.node.addDependency(secretStore);

    // SD-2: ExternalSecret -> k8s Secret "redis-credentials" holding the Redis
    // AUTH token, delivered through the same ESO/IRSA path as the DB creds.
    const redisExternalSecret = cluster.addManifest('RedisExternalSecret', {
      apiVersion: 'external-secrets.io/v1beta1',
      kind: 'ExternalSecret',
      metadata: { name: 'redis-credentials', namespace: appNamespace },
      spec: {
        refreshInterval: '1h',
        secretStoreRef: { name: 'aws-secrets', kind: 'SecretStore' },
        target: { name: 'redis-credentials', creationPolicy: 'Owner' },
        data: [
          {
            secretKey: 'auth-token',
            // The AUTH token secret stores a plain string, so no `property`.
            remoteRef: { key: redisAuthSecret.secretName },
          },
        ],
      },
    });
    redisExternalSecret.node.addDependency(secretStore);

    // -------------------------------------------------------------------------
    // 6. Internal ALB + target group (bound to the Service via TargetGroupBinding)
    // -------------------------------------------------------------------------
    const albSg = new ec2.SecurityGroup(this, 'AlbSg', {
      vpc,
      description: 'Internal ALB for the transfer service',
      allowAllOutbound: true,
    });
    const alb = new elbv2.ApplicationLoadBalancer(this, 'InternalAlb', {
      vpc,
      internetFacing: false,
      securityGroup: albSg,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      // The service makes an authorization decision on a forwarded header
      // (X-Auth-Sub), so reject malformed headers rather than pass them on (MF-8).
      dropInvalidHeaderFields: true,
    });
    const targetGroup = new elbv2.ApplicationTargetGroup(this, 'AppTargets', {
      vpc,
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targetType: elbv2.TargetType.IP, // pod IPs, filled by the LB controller
      healthCheck: {
        path: '/actuator/health',
        healthyHttpCodes: '200',
        interval: cdk.Duration.seconds(15),
      },
    });
    const listener = alb.addListener('Http', {
      port: 80,
      protocol: elbv2.ApplicationProtocol.HTTP,
      // X-Auth-Sub is sanitized only at API Gateway, so the ALB must not be
      // reachable except via the VPC Link. open: false stops CDK adding a
      // 0.0.0.0/0 ingress rule; the VPC-Link SG rule below is the only ingress (MF-6).
      open: false,
      defaultTargetGroups: [targetGroup],
    });
    // ALB reaches pods on 8080 via its default allow-all egress; allow that
    // traffic into the pods' cluster security group.
    cluster.clusterSecurityGroup.addIngressRule(
      ec2.Peer.securityGroupId(albSg.securityGroupId),
      ec2.Port.tcp(8080),
      'ALB to transfer-service pods',
    );

    // -------------------------------------------------------------------------
    // 7. Kubernetes workloads
    // -------------------------------------------------------------------------
    const legacyBaseUrl = `http://mock-legacy.${legacyNamespace}.svc.cluster.local:8080`;

    // --- App Deployment ---
    const appDeployment = cluster.addManifest('AppDeployment', {
      apiVersion: 'apps/v1',
      kind: 'Deployment',
      metadata: { name: 'transfer-service', namespace: appNamespace, labels: { app: 'transfer-service' } },
      spec: {
        replicas: 2,
        selector: { matchLabels: { app: 'transfer-service' } },
        template: {
          metadata: { labels: { app: 'transfer-service' } },
          spec: {
            serviceAccountName: 'transfer-service',
            containers: [
              {
                name: 'app',
                image: appImage.imageUri,
                ports: [{ containerPort: 8080 }],
                env: [
                  { name: 'DB_URL', value: `jdbc:mysql://${aurora.clusterEndpoint.hostname}:3306/limits` },
                  { name: 'DB_USERNAME', valueFrom: { secretKeyRef: { name: 'db-credentials', key: 'username' } } },
                  { name: 'DB_PASSWORD', valueFrom: { secretKeyRef: { name: 'db-credentials', key: 'password' } } },
                  { name: 'REDIS_HOST', value: redis.attrPrimaryEndPointAddress },
                  { name: 'REDIS_PORT', value: '6379' },
                  // SD-2: TLS + AUTH for the pod->Redis hop.
                  { name: 'REDIS_SSL', value: 'true' },
                  { name: 'REDIS_AUTH_TOKEN', valueFrom: { secretKeyRef: { name: 'redis-credentials', key: 'auth-token' } } },
                  { name: 'LEGACY_DAILY_TOTAL_URL', value: `${legacyBaseUrl}/api/daily-total` },
                  { name: 'LEGACY_EXECUTE_URL', value: `${legacyBaseUrl}/api/transfers` },
                  { name: 'LEGACY_STATUS_URL', value: `${legacyBaseUrl}/api/transfers/status` },
                  // SD-1: principal -> customer bindings (see principalCustomerMap context).
                  { name: 'PRINCIPAL_CUSTOMER_MAP', value: principalCustomerMap },
                  // Reconciler runs as a CronJob, not in-process.
                  { name: 'LIMIT_ENFORCEMENT_RECONCILER_ENABLED', value: 'false' },
                  // Expose actuator liveness/readiness probe groups.
                  { name: 'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED', value: 'true' },
                ],
                readinessProbe: {
                  httpGet: { path: '/actuator/health/readiness', port: 8080 },
                  initialDelaySeconds: 20,
                  periodSeconds: 10,
                },
                livenessProbe: {
                  httpGet: { path: '/actuator/health/liveness', port: 8080 },
                  initialDelaySeconds: 40,
                  periodSeconds: 15,
                },
                startupProbe: {
                  httpGet: { path: '/actuator/health', port: 8080 },
                  failureThreshold: 30,
                  periodSeconds: 5,
                },
                resources: {
                  requests: { cpu: '250m', memory: '512Mi' },
                  limits: { cpu: '1', memory: '1Gi' },
                },
              },
            ],
          },
        },
      },
    });
    appDeployment.node.addDependency(appServiceAccount, externalSecret, redisExternalSecret, aurora, redis);

    // --- App Service (ClusterIP; ALB target via TargetGroupBinding) ---
    const appService = cluster.addManifest('AppService', {
      apiVersion: 'v1',
      kind: 'Service',
      metadata: { name: 'transfer-service', namespace: appNamespace },
      spec: {
        type: 'ClusterIP',
        selector: { app: 'transfer-service' },
        ports: [{ port: 80, targetPort: 8080, protocol: 'TCP' }],
      },
    });
    appService.node.addDependency(nsApp);

    // --- TargetGroupBinding: bind the ALB target group to the Service ---
    const tgBinding = cluster.addManifest('AppTargetGroupBinding', {
      apiVersion: 'elbv2.k8s.aws/v1beta1',
      kind: 'TargetGroupBinding',
      metadata: { name: 'transfer-service', namespace: appNamespace },
      spec: {
        serviceRef: { name: 'transfer-service', port: 80 },
        targetGroupARN: targetGroup.targetGroupArn,
        targetType: 'ip',
      },
    });
    tgBinding.node.addDependency(appService);
    if (cluster.albController) {
      tgBinding.node.addDependency(cluster.albController);
    }

    // --- Reconciler CronJob (the "dumb trigger" hitting the internal endpoint) ---
    const reconcileCron = cluster.addManifest('ReconcilerCronJob', {
      apiVersion: 'batch/v1',
      kind: 'CronJob',
      metadata: { name: 'reconciler', namespace: appNamespace },
      spec: {
        schedule: '*/1 * * * *',
        concurrencyPolicy: 'Forbid',
        successfulJobsHistoryLimit: 1,
        failedJobsHistoryLimit: 3,
        jobTemplate: {
          spec: {
            backoffLimit: 0,
            template: {
              spec: {
                restartPolicy: 'Never',
                containers: [
                  {
                    name: 'trigger',
                    image: 'curlimages/curl:8.10.1',
                    command: [
                      'sh',
                      '-c',
                      'curl -fsS -X POST http://transfer-service.app.svc.cluster.local/internal/reconcile',
                    ],
                  },
                ],
              },
            },
          },
        },
      },
    });
    reconcileCron.node.addDependency(appService);

    // --- Mock legacy core (simulated on-prem) ---
    const mockDeployment = cluster.addManifest('MockDeployment', {
      apiVersion: 'apps/v1',
      kind: 'Deployment',
      metadata: { name: 'mock-legacy', namespace: legacyNamespace, labels: { app: 'mock-legacy' } },
      spec: {
        replicas: 1,
        selector: { matchLabels: { app: 'mock-legacy' } },
        template: {
          metadata: { labels: { app: 'mock-legacy' } },
          spec: {
            containers: [
              {
                name: 'mock',
                image: mockImage.imageUri,
                ports: [{ containerPort: 8080 }],
                readinessProbe: { httpGet: { path: '/health', port: 8080 }, initialDelaySeconds: 3 },
                livenessProbe: { httpGet: { path: '/health', port: 8080 }, initialDelaySeconds: 5 },
                resources: {
                  requests: { cpu: '50m', memory: '64Mi' },
                  limits: { cpu: '250m', memory: '128Mi' },
                },
              },
            ],
          },
        },
      },
    });
    mockDeployment.node.addDependency(nsLegacy);

    const mockService = cluster.addManifest('MockService', {
      apiVersion: 'v1',
      kind: 'Service',
      metadata: { name: 'mock-legacy', namespace: legacyNamespace },
      spec: {
        type: 'ClusterIP',
        selector: { app: 'mock-legacy' },
        ports: [{ port: 8080, targetPort: 8080, protocol: 'TCP' }],
      },
    });
    mockService.node.addDependency(nsLegacy);

    // -------------------------------------------------------------------------
    // 8. API Gateway (HTTP API) -> VPC Link -> internal ALB, with Cognito JWT auth
    // -------------------------------------------------------------------------
    const vpcLinkSg = new ec2.SecurityGroup(this, 'VpcLinkSg', {
      vpc,
      description: 'API Gateway VPC Link',
      allowAllOutbound: true,
    });
    // ALB accepts traffic from the VPC Link on the listener port.
    albSg.addIngressRule(ec2.Peer.securityGroupId(vpcLinkSg.securityGroupId), ec2.Port.tcp(80), 'VPC Link to ALB');

    const vpcLink = new apigwv2.VpcLink(this, 'VpcLink', {
      vpc,
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      securityGroups: [vpcLinkSg],
    });

    // SD-1: forward the authenticated Cognito `sub` claim to the backend as the
    // X-Auth-Sub request header. overwriteHeader (not appendHeader) replaces any
    // X-Auth-Sub value the client sent, so the backend only ever sees the `sub`
    // from the JWT the authorizer validated. The Spring app binds this principal
    // to the request's customerId and rejects mismatches (403).
    const integration = new HttpAlbIntegration('AlbIntegration', listener, {
      vpcLink,
      parameterMapping: new apigwv2.ParameterMapping().overwriteHeader(
        'X-Auth-Sub',
        apigwv2.MappingValue.custom('$context.authorizer.claims.sub'),
      ),
    });

    // Fresh Cognito user pool + app client for this deployment.
    const userPool = new cognito.UserPool(this, 'UserPool', {
      userPoolName: 'limit-enforcement-users',
      selfSignUpEnabled: false, // admin-create only
      signInAliases: { email: true, username: true },
      passwordPolicy: {
        minLength: 12,
        requireLowercase: true,
        requireUppercase: true,
        requireDigits: true,
        requireSymbols: true,
      },
      // SD-5: safe by default; destructive teardown is opt-in.
      removalPolicy: demoTeardown ? cdk.RemovalPolicy.DESTROY : cdk.RemovalPolicy.RETAIN,
    });
    const userPoolClient = userPool.addClient('AppClient', {
      userPoolClientName: 'limit-enforcement-client',
      generateSecret: false, // public client so CLI token retrieval needs no SECRET_HASH
      authFlows: { userPassword: true, adminUserPassword: true },
    });

    const authorizer = new HttpJwtAuthorizer(
      'CognitoAuthorizer',
      `https://cognito-idp.${this.region}.amazonaws.com/${userPool.userPoolId}`,
      { jwtAudience: [userPoolClient.userPoolClientId] },
    );

    const httpApi = new apigwv2.HttpApi(this, 'HttpApi', {
      apiName: 'limit-enforcement-api',
      description: 'Public edge for the transfer service (Cognito-protected)',
    });

    // Only the public transfer endpoint is exposed. /internal/* and /stub/* are
    // never routed, so they are unreachable from the internet.
    httpApi.addRoutes({
      path: '/api/transfers',
      methods: [apigwv2.HttpMethod.POST],
      integration,
      authorizer,
    });

    // -------------------------------------------------------------------------
    // Outputs
    // -------------------------------------------------------------------------
    new cdk.CfnOutput(this, 'ApiEndpoint', {
      value: httpApi.apiEndpoint,
      description: 'Base URL of the API Gateway HTTP API',
    });
    new cdk.CfnOutput(this, 'TransfersUrl', {
      value: `${httpApi.apiEndpoint}/api/transfers`,
      description: 'POST here with a Cognito bearer token',
    });
    new cdk.CfnOutput(this, 'UserPoolId', { value: userPool.userPoolId });
    new cdk.CfnOutput(this, 'UserPoolClientId', { value: userPoolClient.userPoolClientId });
    new cdk.CfnOutput(this, 'ClusterName', { value: cluster.clusterName });
    new cdk.CfnOutput(this, 'AuroraEndpoint', { value: aurora.clusterEndpoint.hostname });
    new cdk.CfnOutput(this, 'RedisEndpoint', { value: redis.attrPrimaryEndPointAddress });
    new cdk.CfnOutput(this, 'DbSecretName', { value: dbSecret.secretName });
    new cdk.CfnOutput(this, 'KubeconfigCommand', {
      value: `aws eks update-kubeconfig --name ${cluster.clusterName} --region ${this.region}`,
    });
  }
}
