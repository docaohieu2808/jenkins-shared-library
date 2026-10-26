#!/usr/bin/env bash
# Build the throwaway Jenkins, start it, load the library and the fixture projects as Git repos
# inside it (the way real ones live in Git), and wait until it answers.
set -euo pipefail
pw=$(openssl rand -hex 24)
echo "::add-mask::$pw"
echo "JENKINS_PASSWORD=$pw" >> "${GITHUB_ENV:-/dev/null}"

docker build -t jsl-ci ci
docker run -d --name jenkins -p 8080:8080 -e JENKINS_ADMIN_PASSWORD="$pw" jsl-ci

# Library repo = this checkout without .git; fixtures one repo each.
load_repo() {  # <source dir> <target dir in container>
  docker exec -u jenkins jenkins mkdir -p "$2"
  tar -C "$1" --exclude=.git --exclude=build --exclude=.gradle -c . | docker exec -i -u jenkins jenkins tar -x -C "$2"
  docker exec -u jenkins -w "$2" jenkins bash -ec \
    'git init -q -b master && git -c user.name=ci -c user.email=ci@example.com add -A && git -c user.name=ci -c user.email=ci@example.com commit -q -m ci'
}
load_repo . /var/jenkins_home/library
for f in ci/fixtures/*/; do
  load_repo "$f" "/var/jenkins_home/fixtures/$(basename "$f")"
done

# A real sshd for the ssh deploy target: key login as the jenkins user to localhost. Keys are made at
# start because /var/jenkins_home is a volume and build-time files there would be discarded.
docker exec -u root jenkins usermod -p '*' jenkins
docker exec -u jenkins jenkins bash -ec \
  'mkdir -p ~/.ssh && chmod 700 ~/.ssh && ssh-keygen -q -t ed25519 -N "" -f ~/.ssh/id_ed25519 &&
   cp ~/.ssh/id_ed25519.pub ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
docker exec -d -u root jenkins /usr/sbin/sshd -D -e
for _ in $(seq 1 30); do
  if docker exec -u jenkins jenkins ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new jenkins@localhost true; then break; fi
  sleep 1
done
docker exec -u jenkins jenkins ssh -o BatchMode=yes jenkins@localhost true

for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null -u "admin:$pw" http://localhost:8080/whoAmI/api/json; then echo "Jenkins is up"; exit 0; fi
  sleep 2
done
docker logs jenkins | tail -100
echo "::error::Jenkins did not come up"; exit 1
