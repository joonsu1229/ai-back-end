pipeline {
    agent any

    environment {
        REPO_URL = 'https://github.com/joonsu1229/ai-back-end.git'
        BRANCH = 'master'
        DEPLOY_USER = 'ubuntu'
        DEPLOY_HOST = '217.142.144.114'
        DEPLOY_PATH = '/home/ubuntu/app'

        DB_URL = credentials('DB_URL')
        DB_USERNAME = credentials('DB_USERNAME')
        DB_PASSWORD = credentials('DB_PASSWORD')
        OPENAI_API_KEY = credentials('OPENAI_API_KEY')
    }

    stages {
        stage('Clone') {
            steps {
                git branch: "${BRANCH}", url: "${REPO_URL}"
            }
        }

        stage('Build') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw dependency:resolve'
                sh './mvnw clean package -DskipTests'
                sh '''
                echo "📁 Maven 리포지토리에서 모델 JAR 확인:"
                find ~/.m2/repository -name "*all-minilm-l6-v2*" -type f || echo "모델 JAR 파일을 찾을 수 없음"
                '''
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                set -eux

                echo "▶️ 앱 실행 중지"
                fuser -k 5130/tcp || true

                echo "📦 앱 복사"
                mkdir -p /var/lib/jenkins/app
                cp target/*.jar /var/lib/jenkins/app/app.jar

                echo "🚀 앱 실행"
                touch /var/lib/jenkins/app/log.txt

                nohup java -Dspring.profiles.active=prd \
                           -DDB_URL="${DB_URL}" \
                           -DDB_USERNAME="${DB_USERNAME}" \
                           -DDB_PASSWORD="${DB_PASSWORD}" \
                           -DOPENAI_API_KEY="${OPENAI_API_KEY}" \
                           -Dlangchain.embedding.enabled=false \
                           -jar /var/lib/jenkins/app/app.jar > /var/lib/jenkins/app/log.txt 2>&1

                sleep 5

                echo "📄 로그 미리보기:"
                tail -n 100 /var/lib/jenkins/app/log.txt

                echo "✅ 배포 완료"
                '''
            }
        }
    }
}