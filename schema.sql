-- PostgreSQL 스키마 설정
-- src/main/resources/schema.sql

-- PostgreSQL extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";

-- Documents 테이블
CREATE TABLE IF NOT EXISTS documents (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    category VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    search_vector TSVECTOR
);

-- Full-text search를 위한 인덱스
CREATE INDEX IF NOT EXISTS idx_documents_search_vector 
ON documents USING GIN(search_vector);

-- 카테고리 인덱스
CREATE INDEX IF NOT EXISTS idx_documents_category 
ON documents(category);

-- 제목 인덱스
CREATE INDEX IF NOT EXISTS idx_documents_title 
ON documents(title);

-- tsvector 자동 업데이트를 위한 트리거
CREATE OR REPLACE FUNCTION update_search_vector()
RETURNS TRIGGER AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', 
        COALESCE(NEW.title, '') || ' ' || COALESCE(NEW.content, ''));
    NEW.updated_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_update_search_vector ON documents;
CREATE TRIGGER trigger_update_search_vector
    BEFORE INSERT OR UPDATE ON documents
    FOR EACH ROW
    EXECUTE FUNCTION update_search_vector();

-- 샘플 데이터 삽입
INSERT INTO documents (title, content, category) VALUES
('Spring Boot Guide', 'Spring Boot is a framework that makes it easy to create stand-alone, production-grade Spring based Applications. It provides auto-configuration, embedded servers, and production-ready features.', 'technology'),
('Java 21 Features', 'Java 21 introduces virtual threads, pattern matching, and record patterns. These features improve performance and developer productivity.', 'technology'),
('PostgreSQL Performance', 'PostgreSQL offers excellent performance for both OLTP and OLAP workloads. Full-text search and vector operations are well supported.', 'database'),
('Machine Learning Basics', 'Machine learning is a subset of artificial intelligence that focuses on algorithms that can learn from data without being explicitly programmed.', 'ai'),
('Hybrid Search Implementation', 'Hybrid search combines keyword-based search with semantic search using embeddings. This approach provides better search relevance.', 'technology'),
('Vector Databases', 'Vector databases are optimized for storing and querying high-dimensional vectors, commonly used in AI and ML applications.', 'database'),
('LangChain Tutorial', 'LangChain is a framework for developing applications powered by language models. It provides tools for chaining LLM calls.', 'ai'),
('REST API Design', 'RESTful APIs should follow standard HTTP methods and status codes. Proper resource naming and versioning are important.', 'technology'),
('Database Indexing', 'Database indexes improve query performance but can slow down write operations. Choose indexes carefully based on query patterns.', 'database'),
('Natural Language Processing', 'NLP involves computational techniques for analyzing and generating human language. Modern approaches use transformer models.', 'ai');