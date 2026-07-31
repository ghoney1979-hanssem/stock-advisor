-- Company 마스터 시드 (대형주 일부). 반복 실행 안전(ON CONFLICT DO NOTHING).
-- 적용:  docker exec -i stockadvisor-postgres psql -U stockadvisor -d stockadvisor < scripts/seed-companies.sql
-- corp_code(DART 고유코드)는 DART corpCode.xml 매핑에서 추출한 실제 값.
INSERT INTO company (stock_code, name, corp_code, market) VALUES
  ('005930', '삼성전자',     '00126380', 'KOSPI'),
  ('000660', 'SK하이닉스',   '00164779', 'KOSPI'),
  ('035420', 'NAVER',        '00266961', 'KOSPI'),
  ('035720', '카카오',       '00258801', 'KOSPI'),
  ('005380', '현대차',       '00164742', 'KOSPI'),
  ('247540', '에코프로비엠', '01160363', 'KOSDAQ')
ON CONFLICT (stock_code) DO UPDATE SET corp_code = EXCLUDED.corp_code;
