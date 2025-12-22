#!/bin/bash
# CSV 데이터 임포트 스크립트

echo "CSV 데이터 임포트를 시작합니다..."
echo "애플리케이션이 실행 중이어야 합니다."
echo ""

# API 호출
curl -X POST "http://localhost:7777/api/crawler/import-csv?csvFilePath=db/migration/cool.csv"

echo ""
echo "완료!"
