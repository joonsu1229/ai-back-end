pipeline {
    agent any

    environment {
        REPO_URL = 'https://github.com/joonsu1229/ai-back-end.git'
        BRANCH = 'master'
        DEPLOY_USER = 'ubuntu'
        DEPLOY_HOST = '217.142.144.114'
        DEPLOY_PATH = '/home/ubuntu/app'
    }

    stages {
        stage('Clone') {
            steps {
                git branch: "${BRANCH}", url: "${REPO_URL}"
            }
        }

        stage('Build') {
            steps {
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Deploy') {
            steps {
                sshagent (credentials: ['jenkins-ssh-key']) {
                    sh """
                    scp target/*.jar ${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_PATH}/app.jar
                    ssh ${DEPLOY_USER}@${DEPLOY_HOST} '
                        fuser -k 8080/tcp || true
                        nohup java -jar ${DEPLOY_PATH}/app.jar > ${DEPLOY_PATH}/log.txt 2>&1 &
                    '
                    """
                }
            }
        }
    }
}