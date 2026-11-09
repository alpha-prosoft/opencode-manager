pipelineJob('demo-service-verify') {
  description('Runs the unit tests of a Gerrit patch set of demo-service. Started by gerrit-poll.')
  parameters {
    stringParam('CHANGE', '', 'Gerrit change number')
    stringParam('PATCHSET', '', 'Patch set number')
    stringParam('REF', '', 'refs/changes/... of the patch set')
    stringParam('COMMIT', '', 'Commit of the patch set')
  }
  definition {
    cps {
      sandbox(true)
      script('''
pipeline {
  agent any
  options { skipDefaultCheckout() }
  stages {
    stage('Checkout') {
      steps {
        script { currentBuild.description = "change ${params.CHANGE},${params.PATCHSET} ${params.COMMIT}" }
        deleteDir()
        sh 'git init -q . && git fetch -q "$GERRIT_URL/demo-service" "$REF" && git checkout -q FETCH_HEAD'
      }
    }
    stage('Test') {
      steps {
        sh 'python3 -m unittest -v'
      }
    }
  }
}
''')
    }
  }
}

pipelineJob('gerrit-poll') {
  description('Every minute: starts the verify job of every new patch set of an open Gerrit change.')
  properties {
    pipelineTriggers {
      triggers {
        cron {
          spec('* * * * *')
        }
      }
    }
  }
  definition {
    cps {
      sandbox(true)
      script('''
node {
  def found = sh(script: 'gerrit-poll', returnStdout: true).trim()
  if (found) {
    for (line in found.split('\\n')) {
      def fields = line.split(' ')
      build job: fields[0], wait: false, parameters: [
        string(name: 'CHANGE', value: fields[1]),
        string(name: 'PATCHSET', value: fields[2]),
        string(name: 'REF', value: fields[3]),
        string(name: 'COMMIT', value: fields[4])
      ]
    }
  }
}
''')
    }
  }
}
