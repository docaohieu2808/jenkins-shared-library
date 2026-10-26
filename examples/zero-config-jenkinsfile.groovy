// Jenkinsfile - drop this into any project repo. Language, build, tests, lint and the dependency
// scan are detected and run with no configuration. A Dockerfile adds an image build and scan;
// a deploy target (see the other examples) adds the deploy stages.
@Library('auto-pipeline') _
autoPipeline()
