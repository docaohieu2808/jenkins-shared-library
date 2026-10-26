#!/usr/bin/env bash
# Create a Pipeline job that reads its Jenkinsfile from a Git repo inside the controller, run it and
# assert the result.   run-scm-job.sh <job-name> <repo dir in container> <SUCCESS|FAILURE> [console text to require]
set -euo pipefail
name=${1:?job}; repo=${2:?repo}; expected=${3:?result}; must_contain=${4:-}
url=http://localhost:8080
export JENKINS_USER_ID=admin JENKINS_API_TOKEN="$JENKINS_PASSWORD"
cli=jenkins-cli.jar
[ -f "$cli" ] || curl -fsSL -o "$cli" "$url/jnlpJars/jenkins-cli.jar"

java -jar "$cli" -s "$url" create-job "$name" <<XML
<flow-definition>
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition">
    <scm class="hudson.plugins.git.GitSCM">
      <configVersion>2</configVersion>
      <userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>file://$repo</url></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
      <branches><hudson.plugins.git.BranchSpec><name>*/master</name></hudson.plugins.git.BranchSpec></branches>
    </scm>
    <scriptPath>Jenkinsfile</scriptPath>
    <lightweight>false</lightweight>
  </definition>
</flow-definition>
XML

java -jar "$cli" -s "$url" build "$name" -s -v | tee "console-$name.txt" || true
result=$(curl -fsS -u "admin:$JENKINS_PASSWORD" "$url/job/$name/lastBuild/api/json?tree=result" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"])')
echo "job $name finished: $result (expected $expected)"
[ "$result" = "$expected" ]
if [ -n "$must_contain" ]; then grep -qF -- "$must_contain" "console-$name.txt"; fi
