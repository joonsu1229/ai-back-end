pipeline {
    agent any

    environment {
        REPO_URL = 'https://github.com/joonsu1229/ai-back-end.git'
        BRANCH = 'master'
        DEPLOY_USER = 'ubuntu'
        DEPLOY_HOST = '217.142.144.114'
        DEPLOY_PATH = '/home/ubuntu/app'

        // 기본 설정 및 DB
        DB_URL = credentials('DB_URL')
        DB_USERNAME = credentials('DB_USERNAME')
        DB_PASSWORD = credentials('DB_PASSWORD')
        MODEL_TYPE = credentials('MODEL_TYPE')
        TARGET_DIMENSIONS = credentials('TARGET_DIMENSIONS')

        // AI API 키
        OPENAI_API_KEY = credentials('OPENAI_API_KEY')
        GEMINI_API_KEY = credentials('GEMINI_API_KEY')
        GEMINI_API_EMBED_MODEL = credentials('GEMINI_API_EMBED_MODEL')
        GEMINI_API_CHAT_MODEL = credentials('GEMINI_API_CHAT_MODEL')
        ANTHROPIC_API_KEY = credentials('ANTHROPIC_API_KEY')

        // 크롤링 설정
        CRAWLING_DETAIL_FETCH_METHOD = credentials('CRAWLING_DETAIL_FETCH_METHOD')
        CRAWLING_SCRAPERAPI_KEY = credentials('CRAWLING_SCRAPERAPI_KEY')
        CRAWLING_SCRAPINGBEE_KEY = credentials('CRAWLING_SCRAPINGBEE_KEY')
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

                    APP_DIR="/home/aiPrj/app"
                    LOG_DIR="/home/aiPrj/log"
                    JAR_NAME="aiPrj.jar"
                    PORT="5130"
                    PID_FILE="$APP_DIR/aiPrj.pid"

                    echo "▶️ 기존 프로세스 종료 (포트 $PORT 점유 프로세스)"
                    if [ -f "$PID_FILE" ]; then
                        kill -9 $(cat "$PID_FILE") || true
                    fi
                    fuser -k "$PORT/tcp" || true

                    echo "📦 경로 생성 및 권한 설정"
                    mkdir -p "$APP_DIR" "$LOG_DIR"

                    echo "📦 앱 복사"
                    cp target/*.jar "$APP_DIR/$JAR_NAME"

                    echo "🚀 앱 실행 (환경변수 주입)"
                    BUILD_ID=dontKillMe nohup java -Dspring.profiles.active=prd \
                               -DDB_URL="${DB_URL}" \
                               -DDB_USERNAME="${DB_USERNAME}" \
                               -DDB_PASSWORD="${DB_PASSWORD}" \
                               -DMODEL_TYPE="${MODEL_TYPE}" \
                               -DTARGET_DIMENSIONS="${TARGET_DIMENSIONS}" \
                               -DOPENAI_API_KEY="${OPENAI_API_KEY}" \
                               -DGEMINI_API_KEY="${GEMINI_API_KEY}" \
                               -DGEMINI_API_EMBED_MODEL="${GEMINI_API_EMBED_MODEL}" \
                               -DGEMINI_API_CHAT_MODEL="${GEMINI_API_CHAT_MODEL}" \
                               -DANTHROPIC_API_KEY="${ANTHROPIC_API_KEY}" \
                               -DCRAWLING_DETAIL_FETCH_METHOD="${CRAWLING_DETAIL_FETCH_METHOD}" \
                               -DCRAWLING_SCRAPERAPI_KEY="${CRAWLING_SCRAPERAPI_KEY}" \
                               -DCRAWLING_SCRAPINGBEE_KEY="${CRAWLING_SCRAPINGBEE_KEY}" \
                               -Dlangchain.embedding.enabled=false \
                               -jar "$APP_DIR/$JAR_NAME" \
                               > "$LOG_DIR/aiPrjLog.txt" 2>&1 &
                    echo $! > "$PID_FILE"

                    echo "✅ 배포 완료"
                    echo "📄 로그 모니터링 시작 (Ctrl+C 로 종료)"
                    tail -f "$LOG_DIR/aiPrjLog.txt"
                '''
            }
        }
    }
}