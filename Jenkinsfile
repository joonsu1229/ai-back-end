pipeline {
    agent any

    environment {
        // [1] 레포지토리 및 배포 경로
        REPO_URL = 'https://github.com/joonsu1229/ai-back-end.git'
        BRANCH = 'master'
        APP_DIR = "/home/aiPrj/app"
        LOG_DIR = "/home/aiPrj/log"

        // [2] 데이터베이스 (Credentials ID와 매칭)
        DB_URL = credentials('DB_URL')
        DB_USERNAME = credentials('DB_USERNAME')
        DB_PASSWORD = credentials('DB_PASSWORD')

        // [3] AI 설정 (OpenAI, Gemini, Anthropic)
        MODEL_TYPE = credentials('MODEL_TYPE')
        TARGET_DIMENSIONS = credentials('TARGET_DIMENSIONS')
        OPENAI_API_KEY = credentials('OPENAI_API_KEY')
        GEMINI_API_KEY = credentials('GEMINI_API_KEY')
        GEMINI_API_EMBED_MODEL = credentials('GEMINI_API_EMBED_MODEL')
        GEMINI_API_CHAT_MODEL = credentials('GEMINI_API_CHAT_MODEL')
        ANTHROPIC_API_KEY = credentials('ANTHROPIC_API_KEY')

        // [4] 크롤링 설정 (ScraperAPI, ScrapingBee)
        CRAWLING_DETAIL_FETCH_METHOD = 'selenium' // 혹은 credentials('CRAWLING_DETAIL_FETCH_METHOD')
        CRAWLING_SCRAPERAPI_KEY = credentials('CRAWLING_SCRAPERAPI_KEY')
        CRAWLING_SCRAPINGBEE_KEY = credentials('CRAWLING_SCRAPINGBEE_KEY')
        // URL 등 고정값은 여기서 직접 정의하거나 YAML 기본값을 사용합니다.
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
                sh './mvnw clean package -DskipTests'
            }
        }

        stage('Deploy') {
            steps {
                sh '''
                    set -eux

                    JAR_NAME="aiPrj.jar"
                    PORT="5130"
                    PID_FILE="$APP_DIR/aiPrj.pid"

                    echo "▶️ 기존 프로세스 안전 종료"
                    if [ -f "$PID_FILE" ]; then
                        kill -9 $(cat "$PID_FILE") || true
                        rm -f "$PID_FILE"
                    fi
                    fuser -k "$PORT/tcp" || true

                    echo "📦 환경 정비 및 바이너리 복사"
                    mkdir -p "$APP_DIR" "$LOG_DIR"
                    cp target/*.jar "$APP_DIR/$JAR_NAME"

                    echo "🚀 모든 환경 변수 주입 및 서버 기동"
                    # BUILD_ID=dontKillMe는 Jenkins 빌드가 끝나도 프로세스가 살아남게 합니다.
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

                    echo "⏳ 기동 상태 확인 (15초 대기)"
                    sleep 15
                    if ps -p $(cat "$PID_FILE") > /dev/null; then
                        echo "✅ 배포 성공! 로그 하이라이트:"
                        tail -n 50 "$LOG_DIR/aiPrjLog.txt"
                    else
                        echo "❌ 배포 실패! 에러 로그 확인:"
                        tail -n 100 "$LOG_DIR/aiPrjLog.txt"
                        exit 1
                    fi
                '''
            }
        }
    }
}