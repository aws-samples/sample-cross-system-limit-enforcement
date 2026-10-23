#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { LimitEnforcementStack } from '../lib/limit-enforcement-stack';

const app = new cdk.App();

// Region/account resolution: default to the ambient CLI creds, region us-east-1.
const region = process.env.CDK_DEFAULT_REGION || 'us-east-1';
const account = process.env.CDK_DEFAULT_ACCOUNT;

new LimitEnforcementStack(app, 'LimitEnforcementStack', {
  env: { account, region },
  description: 'Cross-system daily transfer limit enforcement — EKS + API Gateway + Aurora + ElastiCache (demo)',
});

app.synth();
