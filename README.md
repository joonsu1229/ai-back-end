# Hybrid Search with Spring Boot

이 프로젝트는 Spring Boot, Java 21, LangChain, PostgreSQL을 사용한 하이브리드 검색 시스템입니다.

## 주요 기능

- **하이브리드 검색**: 키워드 검색과 의미적 검색을 결합
- **PostgreSQL Full-text Search**: tsvector를 활용한 고성능 텍스트 검색
- **LangChain 통합**: 임베딩 생성 및 의미적 유사도 계산
- **동적 쿼리 빌더**: 다양한 검색 조건 지원
- **리랭킹**: 검색 결과의 품질 향상
- **검색 분석**: 인기 검색어 및 사용 통계

## 기술 스택

- Java 21
- Spring Boot 3.2.0
- PostgreSQL 15+
- LangChain4J
- JPA/Hibernate
- Maven

## 프로젝트 구조

```
hybrid-search/
├── src/
│   ├── main/
│   │   ├── java/com/example/hybridsearch/
│   │   │   ├── HybridSearchApplication.java
│   │   │   ├── controller/
│   │   │   │   ├── SearchController.java
│   │   │   │   ├── DocumentController.java
│   │   │   │   └── AnalyticsController.java
│   │   │   ├── service/
│   │   │   │   ├── HybridSearchService.java
│   │   │   │   ├── EmbeddingService.java
│   │   │   │   ├── QueryBuilderService.java
│   │   │   │   ├── RerankerService.java
│   │   │   │   └── SearchAnalyticsService.java
│   │   │   ├── repository/
│   │   │   │   └── DocumentRepository.java
│   │   │   ├── entity/
│   │   │   │   └── Document.java
│   │   │   ├── dto/
│   │   │   │   └── SearchResult.java
│   │   │   └── config/
│   │   │       └── PerformanceConfig.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── schema.sql
│   └── test/
│       └── java/com/example/hybridsearch/
│           └── HybridSearchApplicationTests.java
├── docker-compose.yml
├── pom.xml
└── README.md
```

## 설정 및 실행

### 1. 데이터베이스 설정

```bash
# Docker를 사용한 PostgreSQL 실행
docker-compose up -d postgres
```

### 2. 환경 변수 설정

```bash
export OPENAI_API_KEY=your-openai-api-key-here
```

### 3. 애플리케이션 빌드 및 실행

```bash
# Maven을 사용한 빌드
mvn clean install

# 애플리케이션 실행
mvn spring-boot:run
```

### 4. 테스트 실행

```bash
mvn test
```

## API 엔드포인트

### 검색 API

#### 하이브리드 검색
```bash
GET /api/search/hybrid?query={query}&category={category}&limit={limit}
```

예시:
```bash
curl "http://localhost:8080/api/search/hybrid?query=Spring Boot&limit=5"
```

#### 키워드 검색
```bash
GET /api/search/keyword?query={query}&category={category}&limit={limit}
```

#### Boolean 검색
```bash
POST /api/search/boolean
Content-Type: application/json

{
  "mustHave": ["Spring", "Boot"],
  "shouldHave": ["framework"],
  "mustNotHave": ["deprecated"],
  "category": "technology",
  "limit": 10
}
```

#### 고급 검색
```bash
GET /api/search/advanced?query={query}&category={category}&useFuzzy=true&usePhrase=false&limit={limit}
```

#### 카테고리별 검색
```bash
GET /api/search/category/{category}?limit={limit}
```

### 문서 관리 API

#### 모든 문서 조회
```bash
GET /api/documents
```

#### 특정 문서 조회
```bash
GET /api/documents/{id}
```

#### 문서 생성
```bash
POST /api/documents
Content-Type: application/json

{
  "title": "문서 제목",
  "content": "문서 내용",
  "category": "카테고리"
}
```

#### 문서 수정
```bash
PUT /api/documents/{id}
Content-Type: application/json

{
  "title": "수정된 제목",
  "content": "수정된 내용",
  "category": "수정된 카테고리"
}
```

#### 문서 삭제
```bash
DELETE /api/documents/{id}
```

### 분석 API

#### 인기 검색어 조회
```bash
GET /api/analytics/popular-queries?limit=10
```

#### 총 검색 횟수 조회
```bash
GET /api/analytics/total-searches
```

## 사용 예시

### 1. 기본 하이브리드 검색
```bash
curl "http://localhost:8080/api/search/hybrid?query=machine learning&limit=5"
```

### 2. 카테고리별 검색
```bash
curl "http://localhost:8080/api/search/hybrid?query=database&category=technology&limit=10"
```

### 3. Boolean 검색
```bash
curl -X POST "http://localhost:8080/api/search/boolean" \
  -H "Content-Type: application/json" \
  -d '{
    "mustHave": ["Java", "Spring"],
    "shouldHave": ["framework", "boot"],
    "mustNotHave": ["deprecated"],
    "category": "technology",
    "limit": 10
  }'
```

### 4. 문서 추가
```bash
curl -X POST "http://localhost:8080/api/documents" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "New Technology Article",
    "content": "This article discusses the latest trends in technology...",
    "category": "technology"
  }'
```

## 검색 타입별 특징

### 1. 키워드 검색 (Keyword Search)
- PostgreSQL의 Full-text Search 기능 활용
- tsvector와 tsquery를 사용한 고성능 텍스트 검색
- 정확한 단어 매칭에 강함

### 2. 의미적 검색 (Semantic Search)
- LangChain4J의 임베딩 모델 활용
- 단어의 의미적 유사성을 고려
- 동의어나 관련 개념 검색에 강함

### 3. 하이브리드 검색 (Hybrid Search)
- 키워드 검색과 의미적 검색의 결합
- 리랭킹을 통한 결과 품질 향상
- 가장 균형잡힌 검색 결과 제공

### 4. Boolean 검색
- AND, OR, NOT 조건 지원
- 정확한 조건 검색이 필요한 경우 사용
- 복합적인 검색 조건 처리

### 5. 퍼지 검색 (Fuzzy Search)
- 오타나 변형된 단어 허용
- Prefix 매칭을 통한 부분 검색
- 사용자 실수에 대한 관용성 제공

## 성능 최적화

### 1. 데이터베이스 최적화
- GIN 인덱스를 통한 Full-text Search 성능 향상
- 카테고리 및 제목 컬럼 인덱싱
- tsvector 자동 업데이트 트리거

### 2. 애플리케이션 최적화
- 비동기 처리를 위한 스레드 풀 설정
- 임베딩 생성 최적화
- 검색 결과 캐싱 준비

### 3. 확장성 고려사항
- Redis를 통한 캐싱 시스템 준비
- 분산 처리를 위한 아키텍처 고려
- 모니터링 및 메트릭 수집 준비

## 개발 환경 설정

### 필수 요구사항
- Java 21
- Maven 3.6+
- PostgreSQL 15+
- Docker (선택사항)

### IDE 설정
- IntelliJ IDEA 또는 Eclipse
- Lombok 플러그인 설치 권장
- Spring Boot 관련 플러그인 설치

### 환경 변수
```env
OPENAI_API_KEY=your-openai-api-key
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/hybrid_search
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=password
```

## 향후 개선 계획

1. **Elasticsearch 연동**: 더 강력한 검색 기능
2. **Redis 캐싱**: 검색 성능 향상
3. **벡터 데이터베이스**: 더 효율적인 임베딩 저장
4. **검색 추천**: 사용자 패턴 기반 추천
5. **실시간 인덱싱**: 문서 업데이트 즉시 반영
6. **다국어 지원**: 여러 언어 검색 지원

## 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다.

## 기여하기

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 문의사항

프로젝트에 대한 문의사항이 있으시면 이슈를 생성해주세요.
