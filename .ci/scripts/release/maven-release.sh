#!/bin/bash

export ZBCTL_ROOT_DIR=${PWD}

mvn -s ${MAVEN_SETTINGS_XML} release:prepare release:perform -B \
  -Dgpg.passphrase="${GPG_PASS}" \
  -Dresume=false \
  -Dtag=${RELEASE_VERSION} \
  -DreleaseVersion=${RELEASE_VERSION} \
  -DdevelopmentVersion=${DEVELOPMENT_VERSION} \
  -DpushChanges=${PUSH_CHANGES} \
  -DremoteTagging=${PUSH_CHANGES} \
  -DlocalCheckout=${SKIP_DEPLOY} \
  -DstagingProgressTimeoutMinutes=240 \
  -Darguments='--settings=${MAVEN_SETTINGS_XML} -DstagingProgressTimeoutMinutes=240 -DskipTests=true -Dgpg.passphrase="${GPG_PASS}" -Dskip.central.release=${SKIP_DEPLOY} -Dskip.camunda.release=${SKIP_DEPLOY} -Dzbctl.force -Dzbctl.rootDir=${ZBCTL_ROOT_DIR}'