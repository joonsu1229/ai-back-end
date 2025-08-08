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
        GEMINI_API_KEY = credentials('GEMINI_API_KEY')
        MODEL_TYPE = credentials('MODEL_TYPE')
        TARGET_DIMENSIONS = credentials('TARGET_DIMENSIONS')
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

                    # 변수를 상단에 모아서 관리하기 용이하게 만듭니다.
                    APP_DIR="/home/aiPrj/app"
                    LOG_DIR="/home/aiPrj/log"
                    JAR_NAME="aiPrj.jar"
                    PORT="5130"
                    PID_FILE="$APP_DIR/aiPrj.pid"

                    echo "▶️ 기존 프로세스 종료 (포트 $PORT 점유 프로세스)"
                    # PID 파일이 존재하는 경우 해당 PID를 사용하여 프로세스를 종료합니다.
                    if [ -f "$PID_FILE" ]; then
                        kill -9 $(cat "$PID_FILE") || true
                    fi
                    fuser -k "$PORT/tcp" || true

                    echo "📦 경로 생성 및 권한 설정"
                    mkdir -p "$APP_DIR" "$LOG_DIR"

                    echo "📦 앱 복사"
                    # Jenkins 워크스페이스의 target 디렉토리에서 빌드된 jar 파일을 복사합니다.
                    cp target/*.jar "$APP_DIR/$JAR_NAME"

                    echo "🚀 앱 실행"
                    # nohup과 &를 사용하여 젠킨스 터미널과 분리하고 백그라운드에서 실행합니다.
                    # BUILD_ID=dontKillMe를 사용하여 Jenkins가 이 프로세스를 종료하지 못하도록 합니다.
                    # 백그라운드 실행 시 생성된 PID를 PID_FILE에 저장합니다.
                    BUILD_ID=dontKillMe nohup java -Dspring.profiles.active=prd \
                               -DMODEL_TYPE="${MODEL_TYPE}" \
                               -DDB_URL="${DB_URL}" \
                               -DDB_USERNAME="${DB_USERNAME}" \
                               -DDB_PASSWORD="${DB_PASSWORD}" \
                               -DOPENAI_API_KEY="${OPENAI_API_KEY}" \
                               -DGEMINI_API_KEY="${GEMINI_API_KEY}" \
                               -DTARGET_DIMENSIONS="${TARGET_DIMENSIONS}" \
                               -Dlangchain.embedding.enabled=false \
                               -jar "$APP_DIR/$JAR_NAME" \
                               > "$LOG_DIR/aiPrjLog.txt" 2>&1 &
                    echo $! > "$PID_FILE"

                    echo "✅ 배포 완료"
                    echo "📄 로그 모니터링 시작 (Ctrl+C 로 종료)"
                    # 애플리케이션이 계속 실행되는 동안 로그를 실시간으로 모니터링합니다.
                    # 이렇게 하면 Jenkins 잡이 계속 실행 중인 상태로 유지됩니다.
                    tail -f "$LOG_DIR/aiPrjLog.txt"
                '''
            }
        }
    }
}